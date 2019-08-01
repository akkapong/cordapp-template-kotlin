package com.template.webserver

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import com.r3.corda.lib.tokens.contracts.utilities.sumTokensOrZero
import com.sun.deploy.net.HttpResponse
import com.template.flows.IssueTHBTokenFlow
import com.template.flows.QueryToken
import com.template.flows.RedeemTHBFlow
import com.template.flows.TransferTHBTokenFlow
import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.getOrThrow
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

/**
 * Define your API endpoints here.
 */
@RestController
@RequestMapping("/THB/token")
class THBTokenController(rpc: NodeRPCConnection) {

    companion object {
        private val logger = LoggerFactory.getLogger(RestController::class.java)
    }

    private val proxy = rpc.proxy

    @GetMapping
    private fun getToken(): ResponseEntity<Any?> {

        logger.info("THBTokenController.getToken GET /THB/token/")

        val flowHandle = proxy.startTrackedFlowDynamic(
                QueryToken::class.java)

        val result = getAvailableToken()
        val status = HttpStatus.OK
        val response = ResponseModel(
                statusCode = status.value(),
                data = result)
        logger.info("THBTokenController.getToken response: $response")
        return ResponseEntity.status(status).body(response)
    }

    private fun getAvailableToken(): List<TokenModel> {

        val flowHandle = proxy.startTrackedFlowDynamic(
                QueryToken::class.java)
        val mapOfKeyAndAmount = mutableMapOf<String, MutableList<Amount<IssuedTokenType>>>()
        flowHandle.use { it.returnValue.getOrThrow() }
                .forEach {
                    val key = it.amount.token.toString()
                    val amounts = mapOfKeyAndAmount.get(key)?: mutableListOf()

                    amounts.add(it.amount)
                    // Update to map
                    mapOfKeyAndAmount.put(key, amounts)
                }

        return mapOfKeyAndAmount.map { (tokenType, amounts) ->
            val firstAmount = amounts.first()
            TokenModel(
                    tokenType = tokenType,
                    issuer = firstAmount.token.issuer.name.organisation,
                    amount = amounts.sumTokensOrZero(firstAmount.token).toDecimal()
            )
        }
    }

    @PostMapping("/issue")
    private fun issueToken(@RequestParam("amount") amount: BigDecimal): ResponseEntity<Any?> {

        logger.info("THBTokenController.issueToken POST /THB/token/issue amount: $amount")

        val (status, response) = try {
            val flowHandle = proxy.startTrackedFlowDynamic(
                    IssueTHBTokenFlow::class.java,
                    amount)

            val result = flowHandle.use { it.returnValue.getOrThrow() }.tx.outputsOfType<FungibleToken>().
                    map { it.amount.toString() }

            val status = HttpStatus.OK
            val response = ResponseModel(
                    statusCode = status.value(),
                    data = result)

            status to response
        } catch (ex: Exception) {
            val status = HttpStatus.EXPECTATION_FAILED
            val response = ResponseModel(
                    statusCode = status.value(),
                    error = status.reasonPhrase,
                    message = ex.toString())
            status to response
        }
        return ResponseEntity.status(status).body(response)
    }

    @PostMapping("/transfer")
    private fun transferToken(@RequestParam("amount") amount: BigDecimal,
                              @RequestParam("newHolder") newHolder: String): ResponseEntity<Any?> {

        logger.info("THBTokenController.transferToken POST /THB/token/transfer amount: $amount, newHolder: $newHolder")

        val (status, response) = try {
            val newHolderParty = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(newHolder))!!
            logger.info("New holder party: $newHolderParty")

            val flowHandle = proxy.startTrackedFlowDynamic(
                    TransferTHBTokenFlow::class.java,
                    amount,
                    newHolderParty)

            flowHandle.use { it.returnValue.getOrThrow() }

            val status = HttpStatus.OK
            val response = ResponseModel(
                    statusCode = status.value(),
                    data = getAvailableToken())

            status to response
        } catch (ex: Exception) {
            logger.info("ERROR: $ex")
            val status = HttpStatus.EXPECTATION_FAILED
            val response = ResponseModel(
                    statusCode = status.value(),
                    error = status.reasonPhrase,
                    message = ex.toString())
            status to response
        }
        return ResponseEntity.status(status).body(response)
    }

    @PostMapping("/redeem")
    private fun redeemToken(@RequestParam("amount") amount: BigDecimal): ResponseEntity<Any?> {

        logger.info("THBTokenController.redeemToken POST /THB/token/redeem amount: $amount")

        val (status, response) = try {
            val flowHandle = proxy.startTrackedFlowDynamic(
                    RedeemTHBFlow::class.java,
                    amount)

            val result = flowHandle.use { it.returnValue.getOrThrow() }.tx.outputsOfType<FungibleToken>().
                    map { it.amount.toString() }

            val status = HttpStatus.OK
            val response = ResponseModel(
                    statusCode = status.value(),
                    data = getAvailableToken())

            status to response
        } catch (ex: Exception) {
            val status = HttpStatus.EXPECTATION_FAILED
            val response = ResponseModel(
                    statusCode = status.value(),
                    error = status.reasonPhrase,
                    message = ex.toString())
            status to response
        }
        return ResponseEntity.status(status).body(response)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
@CordaSerializable
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResponseModel(
        val statusCode: Int? = null,
        val message: String = "SUCCESS",
        val error: String? = null,
        val data: Any? = null)


@JsonIgnoreProperties(ignoreUnknown = true)
@CordaSerializable
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TokenModel(
        val tokenType: String? = null,
        val issuer: String? = null,
        val amount: BigDecimal? = null)