package com.github.ckaag.visualizedtu.visualization

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ckaag.visualizedtu.core.MqttDataPoint
import com.github.ckaag.visualizedtu.core.MqttDataPointRepository
import com.github.ckaag.visualizedtu.writer.VisualizerProperties
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.servlet.view.RedirectView
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.jvm.optionals.getOrNull


@Controller
class DashboardController(
    private val config: VisualizerProperties,
    private val objectMapper: ObjectMapper, private val dashboardService: DashboardService
) {
    @GetMapping
    fun dashboard(
        attributes: RedirectAttributes
    ): RedirectView {
        attributes.addAttribute("start", LocalDate.now().atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        attributes.addAttribute(
            "end",
            LocalDate.now().atStartOfDay().withHour(23).withMinute(59).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
        return RedirectView("/group/${config.pointConfigs[0].chartGroup.encodedGroupName()}")
    }

    @GetMapping("/group/{group}")
    fun singleGroup(
        @PathVariable(value = "group") group: String,
        @RequestParam(name = "start", required = true) start: LocalDateTime,
        @RequestParam(name = "end", required = true) end: LocalDateTime,
        model: Model
    ): String {
        val decodedGroup = URLDecoder.decode(group, StandardCharsets.UTF_8)
        val groupsAndLinks = dashboardService.getGroupsAndLinks()
        model.addAttribute("groups", groupsAndLinks)
        val datasets = dashboardService.getScatterGraphDataSets(decodedGroup, start, end)
        model.addAttribute("datasets", objectMapper.writeValueAsString(datasets.first))
        model.addAttribute(
            "next",
            (datasets.second.next?.let {
                "?start=${
                    it.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                }&end=${it.atStartOfDay().withHour(23).withMinute(59).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}"
            })
        )
        model.addAttribute(
            "previous",
            (datasets.second.previous?.let {
                "?start=${
                    it.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                }&end=${it.atStartOfDay().withHour(23).withMinute(59).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}"
            })
        )
        model.addAttribute(
            "datumHeute",
            start.toLocalDate().format(DateTimeFormatter.ofPattern("dd. MMMM yyyy", java.util.Locale.GERMANY))
        )
        //model.addAttribute("labels", getLabels(datasets))
        model.addAttribute("header", decodedGroup)
        return "dashboard"
    }

    private fun getLabels(datasets: List<DataSet>): List<String> {
        return datasets.flatMap { ds -> ds.data.map { d -> d.toString() }.sorted().distinct() }
    }
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
class DashboardService(private val repo: MqttDataPointRepository, private val config: VisualizerProperties) {

    fun getGroupsAndLinks(): List<LabeledLinks> {
        return config.pointConfigs.map { it.chartGroup }.distinct().sorted().map {
            LabeledLinks(
                it,
                "/group/${it.encodedGroupName()}?start=${
                    LocalDate.now().atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                }&end=${
                    LocalDate.now().atStartOfDay().withHour(23).withMinute(59)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                }"
            )
        }
    }

    fun getScatterGraphDataSets(
        group: String,
        start: LocalDateTime,
        end: LocalDateTime
    ): Pair<List<DataSet>, ForwardBackward> {
        val entries = repo.findAllByChartGroupAndId_IsoTimestampGreaterThanAndId_IsoTimestampLessThan(
            group,
            start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            end.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
        val result = mutableMapOf<String, MutableMap<String, Double>>()

        entries.forEach { p: MqttDataPoint ->
            val dataSet =
                result.computeIfAbsent(p.id.mqttTopic) { return@computeIfAbsent mutableMapOf<String, Double>() }
            dataSet[p.id.isoTimestamp.toGermany()] = p.sumOfValues / p.numberOfValues
        }

        val maxTimestamp = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDate.now().atStartOfDay().plusYears(1000))
        val previous: LocalDate? = (entries.firstOrNull()?.id?.isoTimestamp ?: maxTimestamp)?.let {
            repo.findFirstByChartGroupAndId_IsoTimestampLessThanOrderById_IsoTimestampDesc(
                group,
                it
            )
        }?.getOrNull()?.id?.isoTimestamp?.let {
            ZonedDateTime.parse(it).toLocalDate()
        }
        val minTimestamp = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDate.now().atStartOfDay().minusYears(1000))


        val next: LocalDate? =
            (entries.lastOrNull()?.id?.isoTimestamp ?: (if (previous != null) null else minTimestamp))?.let {
                repo.findFirstByChartGroupAndId_IsoTimestampGreaterThanOrderById_IsoTimestampAsc(
                    group,
                    it
                )
            }?.getOrNull()?.id?.isoTimestamp?.let {
                ZonedDateTime.parse(it).toLocalDate()
            }

        return Pair(
            result.entries.map { (k, v) ->
                DataSet(
                    k,
                    v.entries.sortedBy { it.key }.map { SingleDataPoint(it.key, it.value) })
            },
            if (next == null && previous != null && previous.isAfter(end.toLocalDate())) ForwardBackward(
                null,
                previous
            ) else ForwardBackward(previous, next)
        )
    }
}

private fun String.toGermany(): String {
    return ZonedDateTime.parse(this).withZoneSameLocal(ZoneId.of("Europe/Berlin")).withZoneSameInstant(ZoneOffset.UTC)
        .withZoneSameLocal(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ISO_DATE_TIME)
}

data class ForwardBackward(val previous: LocalDate?, val next: LocalDate?) {

}
