/**
 * Copyright (C) 2014 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms

import java.{util ⇒ ju}

import org.apache.commons.lang3.StringUtils
import org.orbeon.oxf.cache.Cacheable
import org.orbeon.oxf.common.Version
import org.orbeon.oxf.controller.PageFlowControllerProcessor
import org.orbeon.oxf.http.Headers
import org.orbeon.oxf.pipeline.api.PipelineContext
import org.orbeon.oxf.servlet.OrbeonXFormsFilter
import org.orbeon.oxf.util.ScalaUtils._
import org.orbeon.oxf.util.URLRewriterUtils.PathMatcher
import org.orbeon.oxf.util.XPath.CompiledExpression
import org.orbeon.oxf.util._
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.oxf.xforms.XFormsProperties._
import org.orbeon.oxf.xforms.analytics.{RequestStats, RequestStatsImpl}
import org.orbeon.oxf.xforms.event.ClientEvents._
import org.orbeon.oxf.xforms.event.XFormsEvent._
import org.orbeon.oxf.xforms.event.XFormsEvents._
import org.orbeon.oxf.xforms.event.{ClientEvents, XFormsEvent, XFormsEventFactory, XFormsEventTarget}
import org.orbeon.oxf.xforms.processor.XFormsServer
import org.orbeon.oxf.xforms.state.{AnnotatedTemplate, DynamicState}
import org.orbeon.oxf.xforms.xbl.XBLContainer
import org.orbeon.oxf.xml.{XMLReceiver, XMLReceiverSupport}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

private object XFormsContainingDocumentBase {
  val ContainingDocumentPseudoId = "#document"
}

import org.orbeon.oxf.xforms.XFormsContainingDocumentBase._

abstract class XFormsContainingDocumentBase(var disableUpdates: Boolean)
    extends XBLContainer(ContainingDocumentPseudoId, ContainingDocumentPseudoId, "", null, null,null)
    with ContainingDocumentLogging
    with ContainingDocumentMisc
    with ContainingDocumentEvent
    with ContainingDocumentTemplate
    with ContainingDocumentProperties
    with ContainingDocumentRequestStats
    with ContainingDocumentRequest
    with ContainingDocumentDelayedEvents
    with XFormsDocumentLifecycle
    with Cacheable
    with XFormsObject

trait ContainingDocumentMisc {

  self: XBLContainer ⇒

  protected def initializeModels(): Unit =
    initializeModels(
      List(
        XFORMS_MODEL_CONSTRUCT,
        XFORMS_MODEL_CONSTRUCT_DONE,
        XFORMS_READY
      )
    )

  // A sequence number used for dependencies
  // This doesn't need to be serialized/deserialized as it's only used internally
  private var _lastModelSequenceNumber = 0
  def nextModelSequenceNumber() = {
    _lastModelSequenceNumber += 1
    _lastModelSequenceNumber
  }
}

trait ContainingDocumentEvent {

  private var eventStack: List[XFormsEvent] = Nil

  def startHandleEvent(event: XFormsEvent): Unit                = eventStack ::= event
  def endHandleEvent()                    : Unit                = eventStack = eventStack.tail
  def currentEventOpt                     : Option[XFormsEvent] = eventStack.headOption
}

trait ContainingDocumentTemplate extends Logging {

  implicit def indentedLogger: IndentedLogger
  def getStaticState: XFormsStaticState
  def noscript: Boolean

  private var _template: Option[AnnotatedTemplate] = None

  // The page template if available. Only for noscript mode.
  def getTemplate = _template

  // Whether to keep the annotated template in the document itself (dynamic state)
  // See: http://wiki.orbeon.com/forms/doc/contributor-guide/xforms-state-handling#TOC-Handling-of-the-HTML-template
  def setTemplateIfNeeded(template: AnnotatedTemplate): Unit = {

    _template = noscript && getStaticState.template.isEmpty option template |!>
      (t ⇒ debug("keeping XHTML tree", List("approximate size (bytes)" → t.saxStore.getApproximateSize.toString)))
  }

  def restoreTemplate(dynamicState: DynamicState): Unit =
    _template = dynamicState.decodeAnnotatedTemplate
}

trait ContainingDocumentProperties {

  def getStaticState: XFormsStaticState
  def defaultModel: Option[XFormsModel]
  def getRequestStats: RequestStats

  def disableUpdates: Boolean
  def isInitializing: Boolean

  // Whether the document supports updates
  private lazy val _supportUpdates = ! (disableUpdates || isNoUpdates)
  def supportUpdates = _supportUpdates

  // Used by the property() function
  def getProperty(propertyName: String): Any = propertyName match {
    case READONLY_APPEARANCE_PROPERTY ⇒ if (staticReadonly) READONLY_APPEARANCE_STATIC_VALUE else READONLY_APPEARANCE_DYNAMIC_VALUE
    case NOSCRIPT_PROPERTY            ⇒ noscript
    case ENCRYPT_ITEM_VALUES_PROPERTY ⇒ encodeItemValues
    case ORDER_PROPERTY               ⇒ lhhacOrder
    case _                            ⇒ getStaticState.propertyMaybeAsExpression(propertyName).left.get
  }

  private object Memo {
    private val cache = collection.mutable.Map.empty[String, Any]

    private def memo[T](name: String, get: ⇒ Any) =
      cache.getOrElseUpdate(name, get).asInstanceOf[T]

    def staticStringProperty(name: String)  = memo[String] (name, getStaticState.staticProperty(name))
    def staticBooleanProperty(name: String) = memo[Boolean](name, getStaticState.staticProperty(name))
    def staticIntProperty(name: String)     = memo[Int]    (name, getStaticState.staticProperty(name))

    def staticBooleanProperty[T](name: String, pred: T ⇒ Boolean) =
      memo[Boolean](name, pred(getStaticState.staticProperty(name).asInstanceOf[T]))

    def dynamicProperty[T](name: String, convert: String ⇒ T) =
      memo[T](
        name,
        convert(
          getStaticState.propertyMaybeAsExpression(name) match {
            case Left(value) ⇒ value.toString
            case Right(expr) ⇒ evaluateStringPropertyAVT(expr)
          }
        )
      )

    def evaluateStringPropertyAVT(expr: CompiledExpression) =
      defaultModel match {
        case Some(m) ⇒
          val bindingContext = m.getDefaultEvaluationContext
          XPath.evaluateAsString(
            contextItems       = bindingContext.nodeset,
            contextPosition    = bindingContext.position,
            compiledExpression = expr,
            functionContext    = m.getContextStack.getFunctionContext(m.getEffectiveId, bindingContext),
            variableResolver   = m.variableResolver
          )(getRequestStats.getReporter)
        case None ⇒
          throw new AssertionError("can only evaluate AVT properties if a model is present")
      }
  }

  import Memo._

  // Dynamic properties
  def staticReadonly =
    dynamicProperty(
      READONLY_APPEARANCE_PROPERTY,
      _ == READONLY_APPEARANCE_STATIC_VALUE
    )

  def noscript =
    dynamicProperty(
      NOSCRIPT_PROPERTY,
      s ⇒
        if (staticBooleanProperty(NOSCRIPT_SUPPORT_PROPERTY))
          s.toBoolean |!> (Version.instance.isPEFeatureEnabled(_, NOSCRIPT_PROPERTY))
        else
          false
    )

  def encodeItemValues =
    dynamicProperty(
      ENCRYPT_ITEM_VALUES_PROPERTY,
      _.toBoolean
    )

  def lhhacOrder =
    dynamicProperty(
      ORDER_PROPERTY,
      identity
    )

  def hostLanguage =
    dynamicProperty(
      HOST_LANGUAGE,
      identity
    )

  def isNoUpdates =
    dynamicProperty(
      NO_UPDATES,
      _.toBoolean
    )

  def isNoUpdatesStatic =
    getStaticState.propertyMaybeAsExpression(NO_UPDATES) match {
      case Left(value) ⇒ value.toString == "true"
      case _ ⇒ false
    }

  // Static properties
  def isOptimizeGetAllSubmission            = staticBooleanProperty(OPTIMIZE_GET_ALL_PROPERTY)
  def isLocalSubmissionInclude              = staticBooleanProperty(LOCAL_SUBMISSION_INCLUDE_PROPERTY)
  def isLocalInstanceInclude                = staticBooleanProperty(LOCAL_INSTANCE_INCLUDE_PROPERTY)
  def isExposeXPathTypes                    = staticBooleanProperty(EXPOSE_XPATH_TYPES_PROPERTY)
  def isSessionHeartbeat                    = staticBooleanProperty(SESSION_HEARTBEAT_PROPERTY)
  def isXForms11Switch                      = staticBooleanProperty(XFORMS11_SWITCH_PROPERTY)
  def isClientStateHandling                 = staticBooleanProperty[String](STATE_HANDLING_PROPERTY, _ == STATE_HANDLING_CLIENT_VALUE)
  def isReadonlyAppearanceStaticSelectFull  = staticBooleanProperty[String](READONLY_APPEARANCE_STATIC_SELECT_PROPERTY, _ == "full")
  def isReadonlyAppearanceStaticSelect1Full = staticBooleanProperty[String](READONLY_APPEARANCE_STATIC_SELECT1_PROPERTY, _ ==  "full")

  def getLabelElementName                   = staticStringProperty(LABEL_ELEMENT_NAME_PROPERTY)
  def getHintElementName                    = staticStringProperty(HINT_ELEMENT_NAME_PROPERTY)
  def getHelpElementName                    = staticStringProperty(HELP_ELEMENT_NAME_PROPERTY)
  def getAlertElementName                   = staticStringProperty(ALERT_ELEMENT_NAME_PROPERTY)
  def getHintAppearance                     = staticStringProperty(HINT_APPEARANCE_PROPERTY)
  def getHelpAppearance                     = staticStringProperty(HELP_APPEARANCE_PROPERTY)
  def getDateFormatInput                    = staticStringProperty(DATE_FORMAT_INPUT_PROPERTY)

  def getShowMaxRecoverableErrors           = staticIntProperty(SHOW_RECOVERABLE_ERRORS_PROPERTY)
  def getSubmissionPollDelay                = staticIntProperty(ASYNC_SUBMISSION_POLL_DELAY)
  def getAjaxFullUpdateThreshold            = staticIntProperty(AJAX_UPDATE_FULL_THRESHOLD)

  def isLocalSubmissionForward =
    staticBooleanProperty(LOCAL_SUBMISSION_FORWARD_PROPERTY) &&
    staticBooleanProperty(OPTIMIZE_LOCAL_SUBMISSION_REPLACE_ALL_PROPERTY)

  import ContainingDocumentProperties._

  def getTypeOutputFormat(typeName: String) =
    SupportedTypesForOutputFormat(typeName) option
      staticStringProperty(TYPE_OUTPUT_FORMAT_PROPERTY_PREFIX + typeName)

  def getTypeInputFormat(typeName: String) = {
    require(SupportedTypesForInputFormat(typeName))
    staticStringProperty(TYPE_INPUT_FORMAT_PROPERTY_PREFIX + typeName)
  }
}

private object ContainingDocumentProperties {
  val SupportedTypesForOutputFormat = Set("date", "time", "dateTime", "decimal", "integer", "float", "double")
  val SupportedTypesForInputFormat  = Set("date", "time")
}

trait ContainingDocumentLogging {

  private final val indentation = new IndentedLogger.Indentation
  private final val loggersMap = mutable.HashMap[String, IndentedLogger]()

  def getIndentedLogger(loggingCategory: String): IndentedLogger =
    loggersMap.getOrElseUpdate(loggingCategory,
      new IndentedLogger(
        XFormsServer.logger,
        XFormsServer.logger.isDebugEnabled && getDebugLogging.contains(loggingCategory),
        indentation
      )
    )

  val indentedLogger = getIndentedLogger("document")
}

trait ContainingDocumentRequestStats {

  private var _requestStats = RequestStatsImpl()
  def getRequestStats = _requestStats

  def clearRequestStats(): Unit =
    _requestStats = RequestStatsImpl()
}

trait ContainingDocumentRequest {

  private var _deploymentType       : DeploymentType = null
  private var _requestContextPath   : String = null
  private var _requestPath          : String = null
  private var _requestHeaders       : Map[String, List[String]] = null
  private var _requestParameters    : Map[String, List[String]] = null
  private var _containerType        : String = null
  private var _containerNamespace   : String = null
  private var _versionedPathMatchers: ju.List[URLRewriterUtils.PathMatcher] = null
  private var _isEmbedded           : Boolean = false

  def getDeploymentType        = _deploymentType
  def getRequestContextPath    = _requestContextPath
  def getRequestPath           = _requestPath
  def getRequestHeaders        = _requestHeaders
  def getRequestParameters     = _requestParameters
  def getContainerType         = _containerType
  def getContainerNamespace    = _containerNamespace // always "" for servlets.
  def getVersionedPathMatchers = _versionedPathMatchers

  def headersGetter: String ⇒ Option[List[String]] = getRequestHeaders.get

  def isPortletContainer       = _containerType == "portlet"
  def isEmbedded               = _isEmbedded

  private def isEmbeddedFromHeaders(headers: Map[String, List[String]]) =
    Headers.firstHeaderIgnoreCase(headers, Headers.OrbeonClient) exists Headers.EmbeddedClientValues

  protected def initializeRequestInformation(): Unit =
    Option(NetUtils.getExternalContext.getRequest) match {
      case Some(request) ⇒
        // Remember if filter provided separate deployment information

        import OrbeonXFormsFilter._

        val rendererDeploymentType =
          request.getAttributesMap.get(RendererDeploymentAttributeName).asInstanceOf[String]

        _deploymentType =
          rendererDeploymentType match {
            case "separate"   ⇒ DeploymentType.separate
            case "integrated" ⇒ DeploymentType.integrated
            case _            ⇒ DeploymentType.standalone
          }

        // Try to get request context path
        _requestContextPath = request.getClientContextPath("/")

        // It is possible to override the base URI by setting a request attribute. This is used by OrbeonXFormsFilter.
        // NOTE: We used to have response.rewriteRenderURL() on this, but why?
        _requestPath =
          Option(request.getAttributesMap.get(RendererBaseUriAttributeName).asInstanceOf[String]) getOrElse
          request.getRequestPath

        _requestHeaders =
          request.getHeaderValuesMap.asScala mapValues (_.toList) toMap

        _isEmbedded = isEmbeddedFromHeaders(_requestHeaders)

        _requestParameters =
          request.getParameterMap.asScala mapValues StringConversions.objectArrayToStringArray mapValues (_.toList) toMap

        _containerType = request.getContainerType
        _containerNamespace = StringUtils.defaultIfEmpty(request.getContainerNamespace, "")
      case None ⇒
        // Special case when we run outside the context of a request
        _deploymentType = DeploymentType.standalone
        _requestContextPath = ""
        _requestPath = "/"
        _requestHeaders = Map.empty
        _isEmbedded = false
        _requestParameters = Map.empty
        _containerType = "servlet"
        _containerNamespace = ""
    }

  protected def initializePathMatchers(): Unit =
    _versionedPathMatchers =
      Option(PipelineContext.get.getAttribute(PageFlowControllerProcessor.PathMatchers).asInstanceOf[ju.List[PathMatcher]]) getOrElse
        ju.Collections.emptyList[PathMatcher]

  protected def restoreRequestInformation(dynamicState: DynamicState): Unit =
    dynamicState.deploymentType match {
      case Some(_) ⇒
        // Normal case where information below was previously serialized
        _deploymentType     = DeploymentType.valueOf(dynamicState.decodeDeploymentTypeJava)
        _requestContextPath = dynamicState.decodeRequestContextPathJava
        _requestPath        = dynamicState.decodeRequestPathJava
        _requestHeaders     = dynamicState.requestHeaders.toMap
        _isEmbedded         = isEmbeddedFromHeaders(_requestHeaders)
        _requestParameters  = dynamicState.requestParameters.toMap
        _containerType      = dynamicState.decodeContainerTypeJava
        _containerNamespace = dynamicState.decodeContainerNamespaceJava
      case None ⇒
        // Use information from the request
        // This is relied upon by oxf:xforms-submission and unit tests and shouldn't be relied on in other cases
        initializeRequestInformation()
    }

  protected def restorePathMatchers(dynamicState: DynamicState): Unit =
    _versionedPathMatchers = dynamicState.decodePathMatchersJava
}

trait ContainingDocumentDelayedEvents {

  self: XBLContainer ⇒

  private val _delayedEvents = ListBuffer[DelayedEvent]()

  // Schedule an event for delayed execution, following xf:dispatch/@delay semantics
  def addDelayedEvent(
    eventName         : String,
    targetEffectiveId : String,
    bubbles           : Boolean,
    cancelable        : Boolean,
    time              : Long,
    discardable       : Boolean,
    showProgress      : Boolean
  ): Unit = {
    _delayedEvents += DelayedEvent(
      eventName,
      targetEffectiveId,
      bubbles,
      cancelable,
      time,
      discardable,
      showProgress
    )
  }

  def delayedEvents =
    _delayedEvents.toList

  def delayedEventsJava =
    delayedEvents.asJava

  def clearAllDelayedEvents() =
    _delayedEvents.clear()

  def processDueDelayedEvents(): Unit = {

    @tailrec
    def processRemainingBatchesRecursively(): Unit = {

      // Get a fresh time for every batch because processing a batch can take time
      val currentTime = System.currentTimeMillis()

      val (dueEvents, futureEvents) = delayedEvents partition (_.time <= currentTime)

      if (dueEvents.nonEmpty) {

        _delayedEvents.clear()
        _delayedEvents ++= futureEvents

        dueEvents foreach { dueEvent ⇒

          startOutermostActionHandler()

          self.getObjectByEffectiveId(dueEvent.targetEffectiveId) match {
            case eventTarget: XFormsEventTarget ⇒
              ClientEvents.processEvent(
                self.containingDocument,
                XFormsEventFactory.createEvent(
                  eventName  = dueEvent.eventName,
                  target     = eventTarget,
                  properties = EmptyGetter, // NOTE: We don't support properties for delayed events yet.
                  bubbles    = dueEvent.bubbles,
                  cancelable = dueEvent.cancelable
                )
              )
            case _ ⇒
              implicit val logger = self.containingDocument.getIndentedLogger(LOGGING_CATEGORY)
              debug(
                "ignoring delayed event with invalid target id",
                List(
                  "target id"  → dueEvent.targetEffectiveId,
                  "event name" → dueEvent.eventName
                )
              )
          }

          endOutermostActionHandler()
        }

        // Try again in case there are new events available
        processRemainingBatchesRecursively()
      }
    }

    processRemainingBatchesRecursively()
  }
}

case class DelayedEvent(
  eventName         : String,
  targetEffectiveId : String,
  bubbles           : Boolean,
  cancelable        : Boolean,
  time              : Long,
  discardable       : Boolean, // whether the client can discard the event past the delay (see AjaxServer.js)
  showProgress      : Boolean  // whether to show the progress indicator when submitting the event
) {

  private def asEncodedDocument: String = {

    import org.orbeon.oxf.xml.Dom4j._

    XFormsUtils.encodeXML(
      <xxf:events xmlns:xxf="http://orbeon.org/oxf/xml/xforms">
        <xxf:event
          name={eventName}
          source-control-id={targetEffectiveId}
          bubbles={bubbles.toString}
          cancelable={cancelable.toString}/>
      </xxf:events>,
      false
    )
  }

  def writeAsSAX(currentTime: Long)(implicit receiver: XMLReceiver): Unit = {

    import XMLReceiverSupport._

    element(
      localName = "server-events",
      prefix    = "xxf",
      uri       = XXFORMS_NAMESPACE_URI,
      atts      = List(
        "delay"         → (time - currentTime).toString,
        "discardable"   → discardable.toString,
        "show-progress" → showProgress.toString
      ),
      text      = asEncodedDocument
    )
  }

  def writeAsJSON(sb: java.lang.StringBuilder, currentTime: Long): Unit = {
    sb.append('{')
    sb.append("\"delay\":")
    sb.append(time - currentTime)
    if (discardable) {
      sb.append(",\"discardable\":true")
    }
    sb.append(",\"show-progress\":")
    sb.append(showProgress)
    sb.append(",\"event\":\"")
    sb.append(asEncodedDocument)
    sb.append('"')
    sb.append("}")
  }
}