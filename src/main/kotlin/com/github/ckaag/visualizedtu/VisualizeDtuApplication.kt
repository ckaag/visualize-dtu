package com.github.ckaag.visualizedtu

import com.github.ckaag.visualizedtu.writer.VisualizerProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(VisualizerProperties::class)
class VisualizeDtuApplication

fun main(args: Array<String>) {
	runApplication<VisualizeDtuApplication>(*args)
}
