package com.github.ckaag.visualizedtu.core

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import org.springframework.data.repository.CrudRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.*

@Entity
open class MqttDataPoint(
    @EmbeddedId
    open val id: DataPointId,
    @Column(nullable = false)
    open var chartGroup: String,
    @Column(nullable = false)
    open var sumOfValues: Double,
    @Column(nullable = false)
    open var numberOfValues: Int,
)

@Embeddable
open class DataPointId(
    @Column(nullable = false)
    val date: LocalDate,
    @Column(nullable = false)
    val instant: Instant, // null or a 5 minute interval set in the middle
    @Column(nullable = false)
    val mqttTopic: String, // MQTT topic exactly as it was received
)

enum class TimeAggregation {
    DAILY_LAST,
    AVERAGE_5_MINUTES
}
enum class MultiDayAggregation {
    LAST,
    SUM,
    NONE
}

data class PointStreamConfiguration(
    val chartGroup: String,
    val positionFilter: String,
    val aggregation: TimeAggregation,
    val suffixUnit: String,
    val multiDayCombination: MultiDayAggregation = MultiDayAggregation.NONE
)

internal val NIL_INSTANT = Instant.ofEpochSecond(0)

fun ZonedDateTime.toId(config: PointStreamConfiguration, topic: String): DataPointId {
    return DataPointId(this.toLocalDate(), when (config.aggregation) {
        TimeAggregation.DAILY_LAST -> {
            NIL_INSTANT
        }
        TimeAggregation.AVERAGE_5_MINUTES -> {
            this.toInstant().epochSecond.let { epochSeconds -> Instant.ofEpochSecond(epochSeconds - (epochSeconds % 300) + 150) }
        }
    }, mqttTopic = topic)
}

@Suppress("FunctionName")
interface MqttDataPointRepository : CrudRepository<MqttDataPoint, DataPointId> {

    fun findById_DateOrderById_InstantAsc(date: LocalDate): List<MqttDataPoint>

    fun findById_InstantAndId_DateGreaterThanEqualAndId_DateLessThanOrderById_DateAsc(nil: Instant, after: LocalDate, before: LocalDate): List<MqttDataPoint>

    fun findFirstById_DateLessThanOrderById_DateDesc(
        date: LocalDate
    ): Optional<MqttDataPoint>

    fun findFirstById_DateGreaterThanOrderById_DateAsc(
        date: LocalDate
    ): Optional<MqttDataPoint>
}