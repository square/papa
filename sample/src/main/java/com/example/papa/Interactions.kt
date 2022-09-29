package com.example.papa

interface UiInteraction {
  val name: String
    get() = this::class.java.simpleName
}

object TouchLagInteraction : UiInteraction

data class UpdateTextInteraction(val onClick: OnMainActivityButtonClick) : UiInteraction {
  override val name: String
    get() = onClick.element
}

object NeverFinishedInteraction : UiInteraction

interface InteractionEvent

data class OnMainActivityButtonClick(val element: String, val previousText: String, val newText: String) : InteractionEvent

object OnTouchLagClick : InteractionEvent