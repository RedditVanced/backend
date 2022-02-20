package com.github.redditvanced.routing

import com.github.redditvanced.GithubUtils
import com.github.redditvanced.GithubUtils.DispatchInputs
import com.github.redditvanced.database.PluginRepo
import com.github.redditvanced.database.PublishRequest
import com.github.redditvanced.modals.respondError
import dev.kord.common.entity.Snowflake
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.*
import java.util.zip.ZipInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val json = Json {
	ignoreUnknownKeys = true
}

fun Routing.configureGithubWebhook() {
	val webhookKey = System.getenv("GITHUB_WEBHOOK_SECRET")
	if (webhookKey == null) {
		application.log.warn("Missing GITHUB_WEBHOOK_SECRET, disabling Github webhooks!")
		return
	}

	val key = SecretKeySpec(webhookKey.toByteArray(), "HmacSHA1")
	val mac = Mac.getInstance("HmacSHA1").apply { init(key) }

	fun sign(data: String) =
		HexFormat.of().formatHex(mac.doFinal(data.toByteArray()))

	post("github") {
		// Verify request came from GitHub
		val signature = call.request.header("X-Hub-Signature")
		val body = call.receiveText()
		val computed = "sha1=${sign(body)}"

		val isVerified = MessageDigest.isEqual(computed.toByteArray(), signature?.toByteArray())
		if (!isVerified) {
			call.respondError("Could not verify request!", HttpStatusCode.Unauthorized)
			return@post
		}
		call.respond("")

		// Parse data
		val data = json.parseToJsonElement(body)
		if (data.jsonObject["action"]?.jsonPrimitive?.contentOrNull != "completed")
			return@post
		val model = json.decodeFromJsonElement<WebhookWorkflowModel>(data)

		// Extract workflow inputs by downloading the workflow log (refer to: "Echo Inputs" workflow step)
		// This is the best way I found to do this since the GitHub API doesn't provide inputs
		val inputs = try {
			extractWorkflowInputs(model.workflow_run)
		} catch (t: Throwable) {
			return@post
		}
		val workflowConclusion = model.workflow_run.conclusion

		// Fetch publish request, check exists
		val publishRequest = transaction {
			PublishRequest.select {
				PublishRequest.owner eq inputs.owner and
					(PublishRequest.repo eq inputs.repository) and
					(PublishRequest.plugin eq inputs.plugin)
			}.singleOrNull()
		} ?: return@post

		val publishRequestId = publishRequest[PublishRequest.id].value

		// Check MessageId present on publish request
		val messageId = publishRequest[PublishRequest.messageId]
			?: return@post // TODO: handle this?

		// Check Discord message still exists
		val message = try {
			Publishing.discord.channel.getMessage(
				Publishing.publishChannel,
				Snowflake(messageId)
			)
		} catch (t: Throwable) {
			// Delete request if message gone
			transaction {
				PublishRequest.deleteWhere { PublishRequest.id eq publishRequestId }
			}
			return@post
		}

		// Update Discord message
		try {
			Publishing.discord.channel.editMessage(Publishing.publishChannel, Snowflake(messageId)) {
				when (workflowConclusion) {
					"failure" -> {
						content = ":red_circle: **Build failure**\n<${model.workflow_run.html_url}>"
						components = mutableListOf(buildRequestButtons(publishRequestId, false))
					}
					"success" -> {
						content = ":green_circle: Build success"
						components = mutableListOf()
					}
					else -> throw IllegalStateException("Invalid workflow conclusion $workflowConclusion")
				}
			}
		} catch (t: Throwable) {
			application.log.error("Failed to edit message after workflow run ($workflowConclusion). $model. " +
				"Message: https://discord.com/${Publishing.serverId}/${Publishing.publishChannel}/$messageId")
			return@post
		}

		// Update approved commits & delete request
		val expr = PluginRepo.owner eq inputs.owner and
			(PluginRepo.repo eq inputs.plugin)
		val repoExists = transaction {
			PublishRequest.deleteWhere { PublishRequest.id eq publishRequestId }
			PluginRepo.slice(PluginRepo.id).select(expr).singleOrNull() != null
		}

		val (commits) = GithubUtils.getCommits(inputs.owner, inputs.repository, 100)
		transaction {
			if (repoExists) {
				PluginRepo.update({ expr }) {
					it[approvedCommits] = commits.joinToString(",")
				}
			} else {
				PluginRepo.insert {
					it[owner] = inputs.owner
					it[repo] = inputs.repository
					it[approvedCommits] = commits.joinToString(",")
				}
			}
		}
	}
}

private suspend fun extractWorkflowInputs(run: WorkflowRun): DispatchInputs = withContext(Dispatchers.IO) {
	val bytes = GithubUtils.http.get(run.logs_url).body<ByteArray>()
	val zip = ZipInputStream(bytes.inputStream())

	while (true) {
		val entry = zip.nextEntry ?: break
		println(entry.name)
		if (entry.name == "build/2_Echo_Inputs") {
			val log = zip.readNBytes(entry.size.toInt()).decodeToString()
			zip.close()

			val results = "owner:(.+?);repository:(.+?);plugin:(.+?);commit:(.+?);".toRegex().find(log)
				?: throw Error("Could not find inputs in workflow log!")

			val (owner, repository, plugin, commit) = results.destructured
			return@withContext DispatchInputs(owner, repository, plugin, commit)
		}
	}

	throw Error("Could not find step 2 in Github logs!")
}

@Serializable
private data class WebhookWorkflowModel(
	val action: String,
	val workflow_run: WorkflowRun,
)

@Serializable
private data class WorkflowRun(
	val status: String,
	val conclusion: String,
	val html_url: String,
	val logs_url: String,
)
