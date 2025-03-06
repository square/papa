package papa

import kotlin.time.Duration

sealed interface InteractionUpdated<EventType : Any> {
  val interaction: InteractionInFlight<EventType>

  sealed interface WithEvent<EventType : Any> : InteractionUpdated<EventType> {
    val event: SentEvent<EventType>
  }

  sealed interface Canceled<EventType : Any> : InteractionUpdated<EventType>

  data class Started<EventType : Any>(
    override val event: SentEvent<EventType>,
    override val interaction: InteractionInFlight<EventType>
  ) : WithEvent<EventType>

  data class EventRecorded<EventType : Any>(
    override val event: SentEvent<EventType>,
    override val interaction: InteractionInFlight<EventType>
  ) : WithEvent<EventType>

  data class CanceledOnEvent<EventType : Any>(
    override val event: SentEvent<EventType>,
    override val interaction: InteractionInFlight<EventType>,
    val reason: String
  ) : WithEvent<EventType>, Canceled<EventType>

  data class CanceledOnRuleRemoved<EventType : Any>(
    override val interaction: InteractionInFlight<EventType>
  ) : InteractionUpdated<EventType>, Canceled<EventType>

  data class CanceledOnTimeout<EventType : Any>(
    val timeout: Duration,
    override val interaction: InteractionInFlight<EventType>
  ) : InteractionUpdated<EventType>, Canceled<EventType>

  data class Finished<EventType : Any>(
    override val event: SentEvent<EventType>,
    override val interaction: InteractionInFlight<EventType>
  ) : WithEvent<EventType>

  data class FrameRendered<EventType : Any>(
    override val event: SentEvent<EventType>,
    override val interaction: InteractionInFlight<EventType>,
    val frameRenderedUptime: Duration
  ) : WithEvent<EventType>
}