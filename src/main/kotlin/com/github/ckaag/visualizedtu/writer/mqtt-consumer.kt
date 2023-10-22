package com.github.ckaag.visualizedtu.writer

import com.github.ckaag.visualizedtu.core.*
import jakarta.annotation.PostConstruct
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.stereotype.Service
import java.time.ZonedDateTime
import java.util.*
import javax.sql.DataSource


@Configuration
@PropertySource("classpath:persistence.properties")
class SqliteConfig {
}

@Configuration
@EnableJpaRepositories(basePackages = ["com.github.ckaag.visualizedtu"])
class JdbcConfig(private val env: org.springframework.core.env.Environment) {

    @Bean
    fun dataSource(): DataSource {
        val dataSource = DriverManagerDataSource()
        dataSource.setDriverClassName("org.sqlite.JDBC")
        dataSource.url = "jdbc:sqlite:inverterdatamqtt"
        dataSource.username = "sa"
        dataSource.password = "sa"
        return dataSource
    }

    @Bean
    fun entityManagerFactory(): LocalContainerEntityManagerFactoryBean {
        val em = LocalContainerEntityManagerFactoryBean()
        em.setDataSource(dataSource())
        em.setPackagesToScan(*arrayOf("com.github.ckaag.visualizedtu"))
        em.jpaVendorAdapter = HibernateJpaVendorAdapter()
        em.setJpaProperties(additionalProperties())
        return em
    }

    fun additionalProperties(): Properties {
        val hibernateProperties = Properties()
        if (env.getProperty("hibernate.hbm2ddl.auto") != null) {
            hibernateProperties.setProperty("hibernate.hbm2ddl.auto", env.getProperty("hibernate.hbm2ddl.auto"))
        }
        if (env.getProperty("hibernate.dialect") != null) {
            hibernateProperties.setProperty("hibernate.dialect", env.getProperty("hibernate.dialect"))
        }
        if (env.getProperty("hibernate.show_sql") != null) {
            hibernateProperties.setProperty("hibernate.show_sql", env.getProperty("hibernate.show_sql"))
        }
        return hibernateProperties
    }

}


@ConfigurationProperties(prefix = "visualizer")
class VisualizerProperties(
    public var pointConfigs: List<PointStreamConfiguration> = listOf(
        PointStreamConfiguration("Summe Gesamt", "inverter/+/+/YieldTotal", TimeAggregation.DAILY_LAST, "kWh"),
        PointStreamConfiguration("Summe Heute", "inverter/+/+/YieldDay", TimeAggregation.DAILY_LAST, "Wh"),
        PointStreamConfiguration("Heute Watt", "inverter/+/+/P_AC", TimeAggregation.AVERAGE_5_MINUTES, "W"),
        PointStreamConfiguration("Heute Watt", "inverter/+/+/P_DC", TimeAggregation.AVERAGE_5_MINUTES, "W")
    ),
    public var publicIp: String = "192.168.0.163",
    public var mqttPort: String = "1883",
    public var subscriberId: String = "visual-ahoy",
)


@Service
class MqttListener(private val config: VisualizerProperties, private val repo: MqttDataPointRepository) {

    @PostConstruct
    fun postConstruct() {
        val publisher = MqttClient("tcp://" + config.publicIp + ":" + config.mqttPort, config.subscriberId)

        val options = MqttConnectOptions()
        options.isAutomaticReconnect = true
        options.isCleanSession = true
        options.setConnectionTimeout(10)
        publisher.connect(options)

        config.pointConfigs.forEach {
            publisher.subscribe(it.positionFilter) { topic: String?, msg: MqttMessage? ->
                if (topic != null && msg != null) {
                    writeDataPoint(
                        it,
                        topic,
                        msg
                    )
                }
            }
        }

    }

    private fun writeDataPoint(config: PointStreamConfiguration, topic: String, msg: MqttMessage) {
        val id = ZonedDateTime.now().toId(config, topic)

        val entry = repo.findById(id).orElseGet {
            MqttDataPoint(id, config.chartGroup, 0.0, 0)
        }

        val newValue = msg.payload.let { String(it).toDouble() }

        entry.chartGroup = config.chartGroup

        when (config.aggregation) {
            TimeAggregation.DAILY_LAST -> {
                entry.numberOfValues = 1
                entry.sumOfValues = newValue
            }

            TimeAggregation.AVERAGE_5_MINUTES -> {
                entry.numberOfValues += 1
                entry.sumOfValues += newValue
            }

        }
        repo.save(entry)
    }
}