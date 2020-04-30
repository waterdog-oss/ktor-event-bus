package mobi.waterdog.eventbus

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mobi.waterdog.eventbus.containers.KafkaTestContainer
import mobi.waterdog.eventbus.containers.PostgreSQLTestContainer
import mobi.waterdog.eventbus.example.app.LineItem
import mobi.waterdog.eventbus.example.app.Order
import mobi.waterdog.eventbus.example.app.OrderService
import mobi.waterdog.eventbus.example.app.OrderTable
import mobi.waterdog.eventbus.model.EventInput
import mobi.waterdog.eventbus.model.StreamMode
import mobi.waterdog.eventbus.sql.DatabaseConnection
import mobi.waterdog.eventbus.sql.EventTable
import org.amshove.kluent.`should be less than`
import org.amshove.kluent.`should be null`
import org.amshove.kluent.`should equal`
import org.amshove.kluent.`should not be null`
import org.awaitility.Awaitility.await
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.inject
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaIntegrationTest : BaseIntegrationTest() {

    @BeforeAll
    fun initContext() {
        startKoin {
            modules(integrationTestModules)
        }
    }

    @AfterAll
    fun stopContext() {
        stopKoin()
        PostgreSQLTestContainer.instance.stop()
        KafkaTestContainer.instance.stop()
    }

    @AfterEach
    fun cleanEventSenders() {
        val ebp: EventBusProvider by inject()
        ebp.shutdown()
    }

    @Nested
    inner class NonTransactionalIntegrationTest {

        @ParameterizedTest
        @CsvSource(
            "1,1,AutoCommit", "1,5,AutoCommit", "5,1,AutoCommit", "5,5,AutoCommit",
            "1,1,EndOfBatchCommit", "1,5,EndOfBatchCommit", "5,1,EndOfBatchCommit", "5,5,EndOfBatchCommit",
            "1,1,MessageCommit", "1,5,MessageCommit", "5,1,MessageCommit", "5,5,MessageCommit"
        )
        fun `Many consumers and many producers`(numConsumers: Int, numProducers: Int, streamMode: StreamMode) {
            // Given: number of concurrent consumers
            val received = ConcurrentHashMap<String, Int>()
            val topic = UUID.randomUUID().toString()
            repeat(numConsumers) {
                createConsumerThread(topic, streamMode = streamMode) { consumerGroupId, count: Int ->
                    received[consumerGroupId] = count
                }
            }

            // When: there are different numbers of producers sending messages
            val numMessagesPerProducer = 100
            repeat(numProducers) {
                createProducerThread(topic, numMessagesPerProducer)
            }

            // Then: The overall number of received messages is correct
            await()
                .atMost(30, TimeUnit.SECONDS)
                .until {
                    received.isNotEmpty() &&
                        received.map { it.value }
                            .reduce { acc, value -> acc + value } == (numProducers * numConsumers * numMessagesPerProducer)
                }
        }
    }

    @Nested
    inner class CleanupLoopTest {

        @Test
        fun `Cleanup time is respected`() {
            // Given: A producer sending a bunch of messages
            val numSecs = 3600
            val producer = createProducer(numSecs)
            val testTopic = UUID.randomUUID().toString()
            producer.send(EventInput(testTopic, "cleanup_test", "application/json", "[]".toByteArray()))

            // When: Enough time has elapsed since the last message was sent
            val dbc: DatabaseConnection by inject()
            val currentCount = dbc.query { EventTable.select { EventTable.topic eq testTopic }.count() }
            currentCount `should equal` 1

            // Then: The message is retained even after some time has elapsed
            runBlocking { delay(10000L) }
            val updatedCount = dbc.query { EventTable.select { EventTable.topic eq testTopic }.count() }
            updatedCount `should equal` currentCount
        }

        @Test
        fun `Sent events are cleaned up according to the policy defined in the configuration`() {
            // Given: A producer sending a bunch of messages
            val numSecs = 5
            val producer = createProducer(numSecs)
            val testTopic = UUID.randomUUID().toString()
            producer.send(EventInput(testTopic, "cleanup_test", "application/json", "[]".toByteArray()))

            // When: Enough time has elapsed since the last message was sent
            val dbc: DatabaseConnection by inject()
            val currentCount = dbc.query { EventTable.select { EventTable.topic eq testTopic }.count() }
            currentCount `should equal` 1

            // Then: The message is deleted after the time has elapsed
            runBlocking { delay((numSecs + 1) * 1000L) }
            val updatedCount = dbc.query { EventTable.select { EventTable.topic eq testTopic }.count() }
            updatedCount `should be less than` currentCount
        }
    }

    @Nested
    inner class TransactionalIntegrationTest {

        @Test
        fun `Sends an event after some changes have been committed to the database`() {
            // Given: a consumer thread
            val received = ConcurrentHashMap<String, Int>()
            val topic = UUID.randomUUID().toString()
            createConsumerThread(topic) { consumerGroupId, count: Int ->
                received[consumerGroupId] = count
            }

            // And: a block of code that sends an event after a complex insert into a database
            val orderServiceEventProducer = createProducer()
            val service = OrderService(topic, orderServiceEventProducer)
            val newOrder = service.createOrder("ACME Inc.", listOf(LineItem("X", 1)))

            // Then: The expected item is created
            service.getOrderById(newOrder.id).`should not be null`()

            // And: the event is received
            await()
                .atMost(30, TimeUnit.SECONDS)
                .until {
                    received.isNotEmpty() &&
                        received.map { it.value }
                            .reduce { acc, value -> acc + value } == 1
                }
        }

        @Test
        fun `Exceptions are correctly handled when the event is the last thing to be committed`() {
            // Given: a consumer thread
            val received = AtomicBoolean(false)
            val topic = UUID.randomUUID().toString()
            createConsumerThread(topic) { _, _ ->
                received.set(true)
            }

            // And the old count of events in the database
            val dbc: DatabaseConnection by inject()
            val oldCount = dbc.query {
                EventTable.selectAll().count()
            }

            // And: a block of code that throws an exception due to bad data
            var newOrder: Order? = null
            val orderServiceEventProducer = createProducer()
            assertThrows<IllegalArgumentException> {
                val service = OrderService(topic, orderServiceEventProducer)
                newOrder = service.createOrder("ACME Inc.", listOf(LineItem("X", 0)))
            }

            // Then: The expected item is created
            newOrder.`should be null`()

            // And: the number of events remains the same
            val updatedCount = dbc.query {
                EventTable.selectAll().count()
            }
            updatedCount `should equal` oldCount
        }

        @Test
        fun `Exceptions are correctly handled when the event is NOT the last thing to be committed`() {
            // Given: a consumer thread
            val received = AtomicBoolean(false)
            val topic = UUID.randomUUID().toString()
            createConsumerThread(topic) { _, _ ->
                received.set(true)
            }
            val orderServiceEventProducer = createProducer()
            val service = OrderService(topic, orderServiceEventProducer)

            // And the old count of events in the database
            val dbc: DatabaseConnection by inject()
            val oldCount = dbc.query {
                EventTable.selectAll().count()
            }

            val oldOrders = dbc.query {
                OrderTable.selectAll().count()
            }

            // And: a block of code that throws an exception due to bad data
            var newOrder: Order? = null
            assertThrows<IllegalArgumentException> {
                newOrder = service.createOrder(
                    "ACME Inc.", listOf(
                        LineItem("X", 1),
                        LineItem("Y", 0)
                    ), isEvil = true
                )
            }

            // Then: The expected item is created
            newOrder.`should be null`()
            val updatedCount = dbc.query {
                EventTable.selectAll().count()
            }

            val updateOrders = dbc.query {
                OrderTable.selectAll().count()
            }
            updateOrders `should equal` oldOrders
            updatedCount `should equal` oldCount
        }
    }
}