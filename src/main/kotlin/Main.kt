import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Date

fun main(vararg titles: String) {
    for (title in titles) {
        var response = WikipediaBisect.queryWikipedia(title)
        WikipediaBisect.commitIt(response)
        while (response.`continue`?.rvcontinue != null) {
            response = WikipediaBisect.queryWikipedia(
                title = title,
                rvcontinue = response.`continue`!!.rvcontinue
            )
            WikipediaBisect.commitIt(response)
        }
        System.err.println("Done with $title")
    }
    System.exit(0)
}

object WikipediaBisect {
    private val client = OkHttpClient()
    private val objectMapper = ObjectMapper()
        .registerKotlinModule()

    val repoDir = File(".")
    val git = Git.init().setDirectory(repoDir).call()

    fun commitIt(response: WikipediaResponse) {
        val slot = response.query.pages.values.first()
        val revisions = slot.revisions
        for (revision in revisions) {
            val file = File(repoDir, slot.title)
            file.writeText(revision.slots.values.first().body)
            git.add().addFilepattern(slot.title).call()
            if (git.repository.resolve(Constants.HEAD) == null || git.diff().setCached(true).call().isNotEmpty()) {
                val id = PersonIdent(PersonIdent(revision.user, "x@x.x"), Date.from(revision.timestamp))
                git.commit()
                    .setAuthor(id)
                    .setCommitter(id)
                    .setMessage(revision.toCommitMessage())
                    .setOnly(slot.title)
                    .call()
            }
        }
    }

    fun queryWikipedia(title: String, rvcontinue: String? = null): WikipediaResponse {
        val url = HttpUrl.parse("https://en.wikipedia.org/w/api.php?action=query&prop=revisions&rvslots=main&format=json")!!
            .newBuilder()
            .addEncodedQueryParameter("titles", title)
            .addEncodedQueryParameter("rvprop", "content|timestamp|comment|ids|user")
            .addEncodedQueryParameter("rvlimit", "50")
            .addEncodedQueryParameter("rvdir", "newer")
        if (rvcontinue != null) {
            url.addEncodedQueryParameter("rvcontinue", rvcontinue)
        }
        val req = Request.Builder()
            .get()
            .url(url.build())
            .build()
        return client.newCall(req)
            .execute()
            .use { response ->
                response.body().let { body ->
                    if (!response.isSuccessful || body == null) {
                        throw IOException("Coudn't successfull contact ")
                    }
                    val content = body.string()
                    try {
                        objectMapper.readValue(content)
                    } catch (e: IOException) {
                        System.err.println(content)
                        throw e
                    }
                }
            }
    }
}

private fun WikiRevision.toCommitMessage() =
    """
    |$comment

    |revisionId: $revid
    |parentid: $parentid
    """.trimMargin()

@JsonIgnoreProperties("warnings")
data class WikipediaResponse(
    val query: WikiQuery,
    val `continue`: WikiContinue?,
    val batchcomplete: String?
)

data class WikiContinue(
    val `continue`: String?,
    val rvcontinue: String?
)

@JsonIgnoreProperties("normalized")
data class WikiQuery(
    val pages: Map<Int, WikiPage>
)

@JsonIgnoreProperties("ns")
data class WikiPage(
    val pageid: Int,
    val title: String,
    val revisions: List<WikiRevision>
)

class WikiRevision(
    val user: String,
    val anon: String?,
    val parentid: Long,
    timestamp: String,
    val revid: Long,
    val slots: Map<String, WikiSlot>,
    val comment: String
) {
    val timestamp = Instant.parse(timestamp)
}

data class WikiSlot(
    val contentmodel: String,
    val contentformat: String,
    @JsonProperty("*")
    val body: String
)
