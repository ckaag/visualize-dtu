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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


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
        val groupsAndLinks = dashboardService.getGroupsAndLinks()
        model.addAttribute("groups", groupsAndLinks)
        val datasets = dashboardService.getScatterGraphDataSets(group, start, end)
        model.addAttribute("datasets", objectMapper.writeValueAsString(datasets))
        //model.addAttribute("labels", getLabels(datasets))
        model.addAttribute("header", group)
        return "dashboard"
    }

    private fun getLabels(datasets: List<DataSet>): List<String> {
        return datasets.flatMap { ds -> ds.data.map { d -> d.toString() }.sorted().distinct() }
    }
}

data class SingleDataPoint(val x: String, val y: Double)
data class LabeledLinks(val label: String, val href: String)
data class DataSet(val label: String, val data: List<SingleDataPoint>, val borderWidth: Int = 1)

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

    fun getScatterGraphDataSets(group: String, start: LocalDateTime, end: LocalDateTime): List<DataSet> {
        val entries = repo.findAllByChartGroupAndId_IsoTimestampGreaterThanAndId_IsoTimestampLessThan(
            group,
            start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            end.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
        val result = mutableMapOf<String, MutableMap<String, Double>>()

        entries.forEach { p: MqttDataPoint ->
            val dataSet =
                result.computeIfAbsent(p.id.mqttTopic) { return@computeIfAbsent mutableMapOf<String, Double>() }
            dataSet[p.id.isoTimestamp] = p.sumOfValues / p.numberOfValues
        }


        //wenn sollte auch gleich korrekt interpolieren bei fehlenden Einträgen an dieser Stelle, damit alle Datenpunkte vorhanden sind. Aber nur notwendig wenn wir nicht X/Y nehmen

        return result.entries.map { (k, v) ->
            DataSet(
                k,
                v.entries.sortedBy { it.key }.map { SingleDataPoint(it.key, it.value) })
        }
    }
}
