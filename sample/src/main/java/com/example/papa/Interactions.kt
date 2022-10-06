package com.example.papa

interface InteractionEvent

data class OnMainActivityButtonClick(
  val element: String,
  val previousText: String,
  val newText: String
) : InteractionEvent

object OnTouchLagClick : InteractionEvent