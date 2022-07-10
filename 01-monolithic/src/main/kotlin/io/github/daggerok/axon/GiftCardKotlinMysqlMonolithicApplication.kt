package io.github.daggerok.axon

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Version
import org.apache.logging.log4j.kotlin.logger
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.eventhandling.EventHandler
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.modelling.command.AggregateIdentifier
import org.axonframework.modelling.command.AggregateLifecycle
import org.axonframework.modelling.command.TargetAggregateIdentifier
import org.axonframework.queryhandling.QueryGateway
import org.axonframework.queryhandling.QueryHandler
import org.axonframework.spring.stereotype.Aggregate
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME
import org.springframework.http.HttpStatus.CREATED
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation.REQUIRES_NEW
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
class GiftCardKotlinMysqlMonolithicApplication

fun main(args: Array<String>) {
    runApplication<GiftCardKotlinMysqlMonolithicApplication>(*args) {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC))
        Locale.setDefault(Locale.US)
    }
}

// Commands

data class IssueCommand(
    @TargetAggregateIdentifier
    val id: UUID = UUID.fromString("0-0-0-0-0"),
    val amount: BigDecimal = BigDecimal.ZERO,
)

data class RedeemCommand(
    @TargetAggregateIdentifier
    val id: UUID = UUID.fromString("0-0-0-0-0"),
    val amount: BigDecimal = BigDecimal.ZERO,
)

// Events

data class IssuedEvent(
    val id: UUID = UUID.fromString("0-0-0-0-0"),
    val amount: BigDecimal = BigDecimal.ZERO,
)

data class RedeemedEvent(
    val id: UUID = UUID.fromString("0-0-0-0-0"),
    val amount: BigDecimal = BigDecimal.ZERO,
)

// Aggregates

@Aggregate
data class GiftCard(
    @AggregateIdentifier var id: UUID = UUID.fromString("0-0-0-0-0"),
    var remainingValue: BigDecimal = BigDecimal.ZERO,
) {

    @CommandHandler
    constructor(issueCommand: IssueCommand) : this(issueCommand.id, issueCommand.amount) {
        log.info { "handle $issueCommand" }
        if (issueCommand.amount <= BigDecimal.ZERO) throw IllegalArgumentException("amount <= 0")
        AggregateLifecycle.apply(IssuedEvent(issueCommand.id, issueCommand.amount))
    }

    @CommandHandler
    fun handle(redeemCommand: RedeemCommand) {
        log.info { "handle $redeemCommand" }
        if (redeemCommand.amount <= BigDecimal.ZERO) throw IllegalArgumentException("amount(${redeemCommand.amount}) <= 0")
        if (redeemCommand.amount > remainingValue) throw IllegalArgumentException("amount(${redeemCommand.amount}) > remaining($remainingValue)")
        AggregateLifecycle.apply(RedeemedEvent(redeemCommand.id, redeemCommand.amount))
    }

    @EventSourcingHandler
    fun handle(issuedEvent: IssuedEvent) {
        log.info { "handle $issuedEvent" }
        id = issuedEvent.id
        remainingValue = issuedEvent.amount
    }

    @EventSourcingHandler
    fun handle(redeemedEvent: RedeemedEvent) {
        log.info { "handle $redeemedEvent" }
        remainingValue -= redeemedEvent.amount
    }
    
    private companion object {
        val log = logger()
    }
}

// Commands

@RestController
class CommandsResources(private val commandGateway: CommandGateway) {

    @ResponseStatus(CREATED)
    @PostMapping("/issue")
    fun issue(@RequestBody issueCommand: IssueCommand) {
        log.info { "issue: $issueCommand" }
        commandGateway.send<IssueCommand>(issueCommand)
        // val baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
        // return ResponseEntity.created(URI.create("$baseUrl/"))
    }

    @ResponseStatus(CREATED)
    @PostMapping("/redeem")
    fun redeem(@RequestBody redeemCommand: RedeemCommand) {
        log.info { "issue: $redeemCommand" }
        commandGateway.send<IssueCommand>(redeemCommand)
    }

    private companion object {
        val log = logger()
    }
}

// Projection

@Entity
data class GiftCardSummary(

    @Id
    @org.hibernate.annotations.Type(type="uuid-char")
    val id: UUID? = null,

    val amount: BigDecimal = BigDecimal.ZERO,

    val lastRedeemedValue: BigDecimal = BigDecimal.ZERO,

    val remainingValue: BigDecimal = amount - lastRedeemedValue,

    @DateTimeFormat(iso = DATE_TIME)
    @org.hibernate.annotations.UpdateTimestamp
    val lastModifiedAt: Instant? = null,

    @Version
    val version: Long? = null,
)

@Repository
interface GiftCardSummaryRepository : JpaRepository<GiftCardSummary, UUID>

// Query

data class GiftCardSummaryQuery(val id: UUID)

@Component
@Transactional(propagation = REQUIRES_NEW)
class GiftCardSummaryQueryProjector(
    private val queryGateway: QueryGateway,
    private val giftCardSummaryRepository: GiftCardSummaryRepository,
) {

    @EventHandler
    fun handle(issuedEvent: IssuedEvent) =
        GiftCardSummary(id = issuedEvent.id, amount = issuedEvent.amount)
            .also { log.info { "saving $it" } }
            .let { giftCardSummaryRepository.save(it) }
            .also { log.info { "saved $it" } }

    @EventHandler
    fun handle(redeemedEvent: RedeemedEvent): GiftCardSummary =
        giftCardSummaryRepository.findById(redeemedEvent.id)
            .also { log.info { "updating $it" } }
            .map { it.copy(lastRedeemedValue = redeemedEvent.amount, remainingValue = it.remainingValue - redeemedEvent.amount) }
            .map { giftCardSummaryRepository.save(it) }
            .orElseThrow { IllegalArgumentException("cannot handle $redeemedEvent") }
            .also { log.info { "updated $it" } }

    @QueryHandler
    fun handle(giftCardSummaryQuery: GiftCardSummaryQuery): GiftCardSummary =
        giftCardSummaryRepository.findById(giftCardSummaryQuery.id)
            .orElseThrow()

    private companion object {
        val log = logger()
    }
}

@RestController
class QueryResource(private val queryGateway: QueryGateway) {

    @PostMapping
    fun querySummary(@RequestBody query: GiftCardSummaryQuery): CompletableFuture<GiftCardSummary> =
        // queryGateway.query(query, ResponseTypes.instanceOf(GiftCardSummary::class.java))
        queryGateway.query(query, GiftCardSummary::class.java)
}
