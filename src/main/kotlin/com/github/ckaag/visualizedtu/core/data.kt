package com.github.ckaag.visualizedtu.core

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import org.springframework.data.repository.CrudRepository
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
    val isoTimestamp: String, // timestamp after passing pattern filter
    @Column(nullable = false)
    val mqttTopic: String, // MQTT topic exactly as it was received
)


data class PointStreamConfiguration(
    val chartGroup: String,
    val positionFilter: String = "#",
    val timePattern: String = "yyyy-MM-dd'T'HH:mm':00.000Z'", //TODO: or a cron expression instead?
    val combineMethod: String = "AVG", // alternative: LAST
)

fun ZonedDateTime.toId(topic: String, timePattern: String): DataPointId {
    return DataPointId(DateTimeFormatter.ofPattern(timePattern).format(this), topic)
}

interface MqttDataPointRepository : CrudRepository<MqttDataPoint, DataPointId> {
    fun findAllByChartGroupAndId_IsoTimestampGreaterThanAndId_IsoTimestampLessThan(chartGroup: String, start: String, end: String): List<MqttDataPoint>
}