package com.github.ckaag.visualizedtu.visualization

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ckaag.visualizedtu.core.*
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
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*
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
        model.addAttribute("up", "/monthly/${date.withDayOfMonth(1)}")
        return "daily"
    }

    @GetMapping("/monthly/{month}")
    fun monthly(
        @PathVariable("month") month: LocalDate,
        model: Model
    ): String {
        model.addAttribute("next", "/monthly/${month.plusMonths(1)}")
        model.addAttribute("previous", "/monthly/${month.minusMonths(1)}")
        model.addAttribute("down", dashboardService.getDayLinks(month.month, month.year))
        model.addAttribute("up", "/yearly/${month.year}")
        model.addAttribute("data", dashboardService.getDays(month.month, month.year))
        model.addAttribute("datumHeute", "${month.month.getDisplayName(TextStyle.FULL, Locale.GERMANY)} ${month.year}")
        return "monthly"
    }

    @GetMapping("/yearly/{year}")
    fun monthly(
        @PathVariable("year") year: Int,
        model: Model
    ): String {
        model.addAttribute("next", "/yearly/${year + (1)}")
        model.addAttribute("previous", "/yearly/${year - (1)}")
        model.addAttribute("down", dashboardService.getMonthLinks(year))
        model.addAttribute("data", dashboardService.getMonths(year))
        model.addAttribute("datumHeute", "Jahr $year")
        return "yearly"
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

    fun getDays(month: Month, year: Int): List<BarChart> {
        return repo.findById_InstantAndId_DateGreaterThanEqualAndId_DateLessThanOrderById_DateAsc(
            NIL_INSTANT,
            LocalDate.of(year, month, 1),
            LocalDate.of(year, month, 1).plusMonths(1)
        )
            .groupBy { it.chartGroup }.entries
            .mapNotNull { (k, v) ->
                val config = getConfig(v[0].id.mqttTopic)
                if (config.multiDayCombination == MultiDayAggregation.NONE) {
                    return@mapNotNull null
                }
                val rawData = v.groupBy { it.id.mqttTopic }.entries.map { (topic, points) ->
                    BarChartDataSet(topic, points.perDay(config.multiDayCombination))
                }
                val formatted: List<DataSet> = formatDataSet(rawData)
                return@mapNotNull BarChart(
                    k,
                    rawData,
                    objectMapper.writeValueAsString(formatted)
                )
            }
            .toList()
    }

    private fun formatDataSet(rawData: List<BarChartDataSet>): List<DataSet> {
        return rawData.map { bcds ->
            DataSet(
                bcds.label,
                bcds.data.map { SingleDataPoint(it.label, it.value) },
                3,
                false
            )
        }
    }

    fun getMonths(year: Int): List<BarChart> {
        return repo.findById_InstantAndId_DateGreaterThanEqualAndId_DateLessThanOrderById_DateAsc(
            NIL_INSTANT,
            LocalDate.of(year, 1, 1),
            LocalDate.of(year + 1, 1, 1)
        )
            .groupBy { it.chartGroup }.entries
            .mapNotNull { (k, v) ->
                val config = getConfig(v[0].id.mqttTopic)
                if (config.multiDayCombination == MultiDayAggregation.NONE) {
                    return@mapNotNull null
                }
                val rawData = v.groupBy { it.id.mqttTopic }.entries.map { (topic, points) ->
                    BarChartDataSet(topic, points.perMonth(config.multiDayCombination))
                }
                val formatted: List<DataSet> = formatDataSet(rawData)
                return@mapNotNull BarChart(
                    k,
                    rawData,
                    objectMapper.writeValueAsString(formatted)
                )
            }
            .toList()
    }

    private fun getConfig(topic: String): PointStreamConfiguration {
        return config.pointConfigs.find { conf ->
            val substringAfterLast = topic.substringAfterLast('/')
            val positionFilter = conf.positionFilter
            positionFilter.endsWith("/$substringAfterLast")
        }!!
    }

    fun getDayLinks(month: Month, year: Int): List<LabeledLinks> = 1.until(31).mapNotNull { d ->
        try {
            return@mapNotNull LocalDate.of(year, month, d)
        } catch (e: Exception) {
            return@mapNotNull null
        }
    }.map { ld ->
        LabeledLinks(
            ld.format(DateTimeFormatter.ISO_DATE),
            "/daily/${ld.format(DateTimeFormatter.ISO_DATE)}"
        )
    }

    fun getMonthLinks(year: Int): List<LabeledLinks> = 1.until(12).map { LocalDate.of(year, it, 1) }.map { ld ->
        LabeledLinks(
            ld.month.getDisplayName(TextStyle.FULL, Locale.GERMANY),
            "/monthly/${ld.format(DateTimeFormatter.ISO_DATE)}"
        )
    }

    data class ForwardBackward(val previous: LocalDate?, val next: LocalDate?)
}

private fun List<MqttDataPoint>.perMonth(multiDayCombination: MultiDayAggregation): List<BarPoint> {
    return this.groupBy { it.id.date.month }.entries.map { (m, p) ->
        BarPoint(m.getDisplayName(TextStyle.FULL, Locale.GERMANY), p.combined(multiDayCombination))
    }.toList()
}

private fun List<MqttDataPoint>.combined(multiDayCombination: MultiDayAggregation): Double {
    return when (multiDayCombination) {
        MultiDayAggregation.LAST -> this.lastOrNull()?.let { it.sumOfValues / it.numberOfValues } ?: 0.0
        MultiDayAggregation.SUM -> this.sumOf { it.sumOfValues / it.numberOfValues }
        MultiDayAggregation.NONE -> 0.0
    }
}

private fun List<MqttDataPoint>.perDay(multiDayCombination: MultiDayAggregation): List<BarPoint> {
    return this.groupBy { it.id.date.dayOfMonth }.entries.map { (m, p) ->
        BarPoint(m.toString(), p.combined(multiDayCombination))
    }.toList()
}

data class BarPoint(val label: String, val value: Double)

data class BarChartDataSet(val label: String, val data: List<BarPoint>)
data class BarChart(val label: String, val datasets: List<BarChartDataSet>, val dataSetsJson: String) {
}