package com.example.papa

interface UiInteraction

object TouchLagInteraction : UiInteraction

object UpdateTextInteraction : UiInteraction

object NeverFinishedInteraction : UiInteraction

interface InteractionEvent

data class OnMainActivityButtonClick(
  val element: String,
  val previousText: String,
  val newText: String
) : InteractionEvent

object OnTouchLagClick : InteractionEvent