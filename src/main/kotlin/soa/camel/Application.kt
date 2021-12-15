package soa.camel

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.apache.camel.ProducerTemplate
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.dataformat.JsonLibrary
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody


@SpringBootApplication
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

const val DIRECT_ROUTE = "direct:twitter"
const val COUNT_ROUTE = "direct:extractor"
const val LOG_ROUTE = "direct:log"

@Controller
class SearchController(private val producerTemplate: ProducerTemplate) {
    @RequestMapping("/")
    fun index() = "index"

    @RequestMapping(value = ["/search"])
    @ResponseBody
    fun search(@RequestParam("q") q: String?): Any =
        producerTemplate.requestBodyAndHeader(DIRECT_ROUTE, "mandalorian", "keywords", q)
}

@Component
class Router(meterRegistry: MeterRegistry) : RouteBuilder() {

    private val perKeywordMessages = TaggedCounter("per-keyword-messages", "keyword", meterRegistry)


    override fun configure() {
        from(DIRECT_ROUTE)
        .process { exchange ->
            val regex = "max:[0-9]+".toRegex()
            var keywords = exchange.`in`.getHeader("keywords").toString()
            regex.find(keywords)?.let { match ->
                val max = match.value.split(":")[1].toInt()
                keywords = keywords.replace(regex, "")
                keywords += "?count=$max"
                exchange.`in`.setHeader("keywords", keywords)
            }
        }
            .toD("twitter-search:\${header.keywords}")
            .wireTap(LOG_ROUTE)
            .wireTap(COUNT_ROUTE)

        from(LOG_ROUTE)
            .marshal().json(JsonLibrary.Gson)
            .to("file://log?fileName=\${date:now:yyyy/MM/dd/HH-mm-ss.SSS}.json")

        from(COUNT_ROUTE)
            .split(body())
            .process { exchange ->
                val keyword = exchange.getIn().getHeader("keywords")
                if (keyword is String) {
                    keyword.split(" ").map {
                        perKeywordMessages.increment(it)
                    }
                }
            }
        }
    }

class TaggedCounter(private val name: String, private val tagName: String, private val registry: MeterRegistry) {
    private val counters: MutableMap<String, Counter> = HashMap()
    fun increment(tagValue: String) {
        counters.getOrPut(tagValue) {
            Counter.builder(name).tags(tagName, tagValue).register(registry)
        }.increment()
    }
}

