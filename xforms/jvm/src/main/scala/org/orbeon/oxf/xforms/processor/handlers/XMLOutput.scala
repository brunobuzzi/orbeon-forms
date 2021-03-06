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
package org.orbeon.oxf.xforms.processor.handlers

import java.{lang ⇒ jl}

import org.orbeon.oxf.externalcontext.ExternalContext
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.oxf.xforms.analysis.controls.{AppearanceTrait, LHHA}
import org.orbeon.oxf.xforms.control._
import org.orbeon.oxf.xforms.control.controls._
import org.orbeon.oxf.xforms.itemset.Item
import org.orbeon.oxf.xforms.state.AnnotatedTemplate
import org.orbeon.oxf.xforms.{XFormsConstants, XFormsContainingDocument, XFormsUtils}
import org.orbeon.oxf.xml.Dom4j._
import org.orbeon.oxf.xml._

//
// TODO:
//
// - check custom MIPs naming
// - multiple alerts
// - incremental
// - select1 getGroupName
//
// Could have configuration tokens, e.g.:
//
// - prune non-relevant controls
// - prune internals of XBL value controls
// - show XFormsVariableControl | XXFormsAttributeControl | XFormsActionControl | internal XFormsGroupControl
//
object XMLOutput extends XMLReceiverSupport {

  def send(
    xfcd            : XFormsContainingDocument,
    template        : AnnotatedTemplate,
    externalContext : ExternalContext)(implicit
    xmlReceiver     : XMLReceiver
  ): Unit =
    withDocument {
      xfcd.getControls.getCurrentControlTree.rootOpt foreach (root ⇒ applyMatchers(root, xmlReceiver))
    }

  def writeTextOrHTML(name: String, value: String, html: Boolean)(implicit xmlReceiver: XMLReceiver): Unit =
    if (html)
      withElement(name, atts = List("html" → true.toString)) {
        XFormsUtils.streamHTMLFragment(xmlReceiver, value, null, "")
      }
    else
      element(name, text = value)

  def matchLHHA(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c collect {
    case c: XFormsControl ⇒
      implicit val _xmlReceiver = xmlReceiver
      for {
        lhhaType ← LHHA.values
        lhhaProp = c.lhhaProperty(lhhaType)
        text     ← Option(lhhaProp.value())
      } locally {
        writeTextOrHTML(lhhaType.entryName, text, lhhaProp.isHTML)
      }
  }

  def matchAppearances(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c.staticControl collect {
    case c: AppearanceTrait ⇒
      implicit val _xmlReceiver = xmlReceiver

      c.appearances.iterator map
      (AppearanceTrait.encodeAppearanceValue(new jl.StringBuilder, _).toString) foreach
      (appearance ⇒ element("appearance", text = appearance))
  }

  def matchSingleNode(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c collect {
    case c: XFormsSingleNodeControl ⇒
      implicit val _xmlReceiver = xmlReceiver
      element(
        "mips",
        atts = List(
          "readonly" → c.isReadonly.toString,
          "required" → c.isRequired.toString,
          "valid"    → c.isValid.toString
        ) ++
          (c.valueTypeOpt.toList map (t ⇒ "datatype" → t.uriQualifiedName)) ++
          c.customMIPs // CHECK
      )
  }

  def matchValue(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c collect {
    case c: XFormsValueControl if c.isRelevant ⇒
      implicit val _xmlReceiver = xmlReceiver

      // XBL: Should probably do via xforms:htmlFragment and/or possibly the XBL control exposing a mediatype in
      // its definition.
      val isHTML =
        c.mediatype.contains("text/html") ||
        c.isInstanceOf[XFormsComponentControl] && c.staticControl.localName == "tinymce"

      writeTextOrHTML("value", c.getValue, isHTML)
      c.externalValueOpt filter (_ != c.getValue) foreach (writeTextOrHTML("external-value", _, isHTML))
  }

  def matchVisitable(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c collect {
    case c: VisitableTrait if c.isRelevant ⇒
      implicit val _xmlReceiver = xmlReceiver
      if (c.visited)
        element("visited", text = c.visited.toString)
  }

  def matchItemset(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c collect {
    case c: XFormsSelect1Control if c.isRelevant ⇒
      implicit val _xmlReceiver = xmlReceiver
      withElement("items") {
        c.getItemset.allItemsIterator foreach {
          case item @ Item(_, _, _, value, atts) ⇒
            val attsList = atts map { case (k, v) ⇒ k.uriQualifiedName → v }
            withElement("item", atts = List("value" → value) ++ attsList) {
              item.iterateLHHA foreach { case (name, lhha) ⇒
                writeTextOrHTML(name, lhha.label, lhha.isHTML)
              }
            }
        }
      }
  }

  def matchContainer(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c collect {
    case c: XFormsContainerControl ⇒
      c.children foreach (applyMatchers(_, xmlReceiver))
  }

  def matchRepeat(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c collect {
    case c: XFormsRepeatControl if c.isRelevant ⇒
      implicit val _xmlReceiver = xmlReceiver
      element("repeat", atts = List("index" → c.getIndex.toString))
  }

  def matchSwitchCase(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c collect {
    case c: XFormsSwitchControl if c.isRelevant ⇒
      implicit val _xmlReceiver = xmlReceiver
      element("switch", atts = List("selected" → (c.selectedCaseIfRelevantOpt map (_.getId) orNull)))
  }

  def matchFileMetadata(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c collect {
    case c: FileMetadata ⇒
      implicit val _xmlReceiver = xmlReceiver

      val properties = c.iterateProperties collect { case (k, Some(v)) ⇒ k → v } toList

      if (properties.nonEmpty)
        element("file-metadata", atts = properties)
  }

  def matchDialog(c: XFormsControl, xmlReceiver: XMLReceiver): Unit = c collect {
    case c: XXFormsDialogControl ⇒
      implicit val _xmlReceiver = xmlReceiver
      if(c.isVisible)
        element("visible", text = "true")
  }

  val Matchers =
    List[(XFormsControl, XMLReceiver) ⇒ Unit](
      matchAppearances,
      matchLHHA,
      matchSingleNode,
      matchValue,
      matchVisitable,
      matchItemset,
      matchRepeat,
      matchSwitchCase,
      matchDialog,
      matchFileMetadata,
      matchContainer
    )

  def applyMatchers(c: XFormsControl, xmlReceiver: XMLReceiver) = c match {
    case _: XFormsVariableControl | _: XXFormsAttributeControl | _: XFormsActionControl ⇒
      // Skip control and its descendants
    case c: XFormsGroupControl if c.appearances(XFormsConstants.XXFORMS_INTERNAL_APPEARANCE_QNAME) ⇒
      // Skip control but process descendants
      matchContainer(c, xmlReceiver)
    case _ ⇒
      implicit val _xmlReceiver = xmlReceiver

      val baseAttributes = List(
        "id"       → c.getEffectiveId,
        "type"     → c.staticControl.localName,
        "relevant" → c.isRelevant.toString
      )

      val mediatypeAttribute =
        c.mediatype.toList map ("mediatype" →)

      val extensionAttributes =
        c.evaluatedExtensionAttributes.iterator collect {
          case (name, value) if name.namespace.uri == "" ⇒ name.name → value
        }

      withElement("control", atts = baseAttributes ++ mediatypeAttribute ++ extensionAttributes) {
        Matchers foreach (_.apply(c, xmlReceiver))
      }
  }
}
