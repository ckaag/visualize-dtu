package com.github.ckaag.visualizedtu.visualization

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ckaag.visualizedtu.core.MqttDataPoint
import com.github.ckaag.visualizedtu.core.MqttDataPointRepository
import com.github.ckaag.visualizedtu.core.PointStreamConfiguration
import com.github.ckaag.visualizedtu.core.TimeAggregation
import com.github.ckaag.visualizedtu.writer.VisualizerProperties
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.servlet.view.RedirectView
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.jvm.optionals.getOrNull


@Controller
class DashboardController(private val dashboardService: DashboardService) {
    @GetMapping
    fun dashboard(
        attributes: RedirectAttributes
    ): RedirectView {
        return RedirectView("/daily/${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}")
    }

    @GetMapping("/daily/{date}")
    fun daily(
        @PathVariable(value = "date") date: LocalDate,
        model: Model
    ): String {
        model.addAttribute(
            "datumHeute",
            date.format(DateTimeFormatter.ofPattern("dd. MMMM yyyy", java.util.Locale.GERMANY))
        )
        val metrics = dashboardService.getMetrics(date)
        model.addAttribute(
            "metrics",
            metrics.first
        )
        model.addAttribute(
            "next",
            metrics.second.next?.let { d -> "/daily/" + d.format(DateTimeFormatter.ISO_DATE) }
        )
        model.addAttribute(
            "previous",
            metrics.second.previous?.let { d -> "/daily/" + d.format(DateTimeFormatter.ISO_DATE) }
        )
        return "daily"
    }


    data class Metric(val label: String, val suffixUnit: String, val directValue: Double?, val dataSetsJson: String?)
}

data class SingleDataPoint(val x: String, val y: Double)
data class LabeledLinks(val label: String, val href: String)
data class DataSet(
    val label: String,
    val data: List<SingleDataPoint>,
    val borderWidth: Int = 2,
    val fill: Boolean = true
)

private fun String.encodedGroupName(): String {
    return URLEncoder.encode(this, StandardCharsets.UTF_8)
}

@Service
class DashboardService(
    private val repo: MqttDataPointRepository,
    private val config: VisualizerProperties,
    private val objectMapper: ObjectMapper
) {

    fun getMetrics(date: LocalDate): Pair<List<DashboardController.Metric>, ForwardBackward> {
        val allData = repo.findById_DateOrderById_InstantAsc(date)
        return Pair(
            config.pointConfigs.groupBy { it.chartGroup }.map { (chartGroup, props) ->
                DashboardController.Metric(
                    chartGroup,
                    props[0].suffixUnit,
                    if (props[0].aggregation === TimeAggregation.DAILY_LAST) {
                        allData.find { it.chartGroup == chartGroup }?.let { it.sumOfValues / it.numberOfValues }
                    } else {
                        null
                    },
                    if (props[0].aggregation === TimeAggregation.AVERAGE_5_MINUTES) {
                        objectMapper.writeValueAsString(get5MinuteAverages(allData, date, chartGroup, props))
                    } else {
                        null
                    },
                )
            },
            ForwardBackward(
                repo.findFirstById_DateLessThanOrderById_DateDesc(date).getOrNull()?.id?.date,
                repo.findFirstById_DateGreaterThanOrderById_DateAsc(date).getOrNull()?.id?.date
            )
        )
    }

    fun get5MinuteAverages(
        allData: List<MqttDataPoint>,
        date: LocalDate,
        chartGroup: String,
        props: List<PointStreamConfiguration>
    ): List<DataSet> {
        return allData.filter { it.chartGroup == chartGroup }.groupBy { it.id.mqttTopic }.entries.map { (key, value) ->
            DataSet(
                key,
                value.map { SingleDataPoint(it.id.instant.toGermanyIsoTime(), it.sumOfValues / it.numberOfValues) })
        }
    }

    private fun Instant.toGermanyIsoTime(): String {
        return this.atZone(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    data class ForwardBackward(val previous: LocalDate?, val next: LocalDate?)
}
