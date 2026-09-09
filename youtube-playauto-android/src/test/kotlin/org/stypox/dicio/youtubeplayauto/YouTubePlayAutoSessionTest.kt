package org.stypox.dicio.youtubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class YouTubePlayAutoSessionTest : StringSpec({
    val song = "Đừng Xa Em Đêm Nay"

    fun edit(query: String, ime: Boolean = true) = YouTubeNodeFact(
        className = "android.widget.EditText",
        text = query,
        editable = true,
        focused = true,
        hasImeEnterAction = ime,
    )

    fun suggestion(text: String) = YouTubeNodeFact(text = text, clickable = true)

    fun video(title: String, meta: String = "4:12 • 1.2M views") = YouTubeNodeFact(
        text = "$title $meta",
        clickable = true,
    )

    fun searchButton() = YouTubeNodeFact(
        text = "Search",
        clickable = true,
        className = "android.widget.ImageButton",
    )

    fun suggestionFacts(ime: Boolean = true) = YouTubeUiFacts(
        query = song,
        submitted = false,
        nodes = listOf(
            edit(song, ime),
            suggestion(song),
            suggestion("$song remix"),
            suggestion("$song karaoke"),
        ),
    )

    fun resultsFacts() = YouTubeUiFacts(
        query = song,
        submitted = true,
        nodes = listOf(
            YouTubeNodeFact(text = song, editable = true, focused = false),
            YouTubeNodeFact(text = "All", clickable = true),
            video("$song - Official Audio"),
            video("$song remix"),
        ),
    )

    "query entered then submit exactly once" {
        val session = YouTubePlayAutoSession(song)
        val first = session.onUi(suggestionFacts())
        first.shouldBeInstanceOf<YouTubePlayAutoAction.SubmitSearch>()
        first.method shouldBe YouTubeSearchSubmitMethod.IME
        session.submitCount shouldBe 1
        session.diagnostics.queryEntered shouldBe true
        session.diagnostics.searchSubmitAttempted shouldBe true
        session.onUi(suggestionFacts()).shouldBeInstanceOf<YouTubePlayAutoAction.Wait>()
        session.submitCount shouldBe 1
        session.selectCount shouldBe 0
    }

    "suggestion screen must not select suggestion as video" {
        val session = YouTubePlayAutoSession(song)
        val action = session.onUi(suggestionFacts())
        action.shouldBeInstanceOf<YouTubePlayAutoAction.SubmitSearch>()
        action.shouldBeInstanceOf<YouTubePlayAutoAction.SubmitSearch>()
        YouTubeSearchUiClassifier.kind(suggestionFacts()) shouldBe
            YouTubeSearchUiKind.SEARCH_INPUT_OR_SUGGESTIONS
        YouTubeSearchUiClassifier.isSuggestionText("$song remix", song) shouldBe true
        YouTubeSearchUiClassifier.isSuggestionText("$song karaoke", song) shouldBe true
        session.selectCount shouldBe 0
    }

    "submit success waits for results and does not click yet" {
        val session = YouTubePlayAutoSession(song)
        session.onUi(suggestionFacts())
        val waiting = session.onUi(
            suggestionFacts().copy(submitted = true),
        )
        waiting shouldBe YouTubePlayAutoAction.Wait
        session.diagnostics.stage shouldBe YouTubePlayAutoStage.WAITING_RESULTS
        session.selectCount shouldBe 0
        session.diagnostics.resultSelected shouldBe false
    }

    "results ready runs matcher and clicks best video once" {
        val session = YouTubePlayAutoSession(song)
        session.onUi(suggestionFacts())
        val click = session.onUi(resultsFacts())
        click.shouldBeInstanceOf<YouTubePlayAutoAction.ClickVideo>()
        click.title shouldBe "$song - Official Audio 4:12 • 1.2M views"
        session.selectCount shouldBe 1
        session.diagnostics.resultsPageDetected shouldBe true
        session.diagnostics.candidateVideoCount shouldBe 2
        session.onUi(resultsFacts()) shouldBe YouTubePlayAutoAction.None
        session.selectCount shouldBe 1
    }

    "results not ready produces no click" {
        val session = YouTubePlayAutoSession(song)
        session.onUi(suggestionFacts())
        repeat(4) {
            session.onUi(suggestionFacts().copy(submitted = true)) shouldBe YouTubePlayAutoAction.Wait
        }
        session.selectCount shouldBe 0
    }

    "no matching video results in no click and failure" {
        val session = YouTubePlayAutoSession(song)
        session.onUi(suggestionFacts())
        val fail = session.onUi(
            YouTubeUiFacts(
                query = song,
                submitted = true,
                nodes = listOf(
                    YouTubeNodeFact(text = "All", clickable = true),
                    video("Completely Different Track"),
                ),
            ),
        )
        fail.shouldBeInstanceOf<YouTubePlayAutoAction.Fail>()
        fail.reason shouldBe "no_matching_video"
        session.selectCount shouldBe 0
        session.diagnostics.stage shouldBe YouTubePlayAutoStage.FAILED
    }

    "duplicate accessibility events do not duplicate submit" {
        val session = YouTubePlayAutoSession(song)
        session.onUi(suggestionFacts()).shouldBeInstanceOf<YouTubePlayAutoAction.SubmitSearch>()
        repeat(5) {
            session.onUi(suggestionFacts()) shouldBe YouTubePlayAutoAction.Wait
        }
        session.submitCount shouldBe 1
        session.submitCount shouldBeLessThanOrEqual 1
    }

    "duplicate result events do not duplicate video click" {
        val session = YouTubePlayAutoSession(song)
        session.onUi(suggestionFacts())
        session.onUi(resultsFacts()).shouldBeInstanceOf<YouTubePlayAutoAction.ClickVideo>()
        repeat(5) {
            session.onUi(resultsFacts()) shouldBe YouTubePlayAutoAction.None
        }
        session.selectCount shouldBe 1
    }

    "timeout failure leaves session failed without extra click" {
        val session = YouTubePlayAutoSession(song)
        session.onUi(suggestionFacts())
        val fail = session.timeout()
        fail.shouldBeInstanceOf<YouTubePlayAutoAction.Fail>()
        session.selectCount shouldBe 0
        session.diagnostics.failureReason shouldBe "select_timeout"
    }

    "search button is used when IME enter is absent" {
        val session = YouTubePlayAutoSession(song)
        val facts = YouTubeUiFacts(
            query = song,
            submitted = false,
            nodes = listOf(edit(song, ime = false), searchButton(), suggestion("$song remix")),
        )
        val action = session.onUi(facts).shouldBeInstanceOf<YouTubePlayAutoAction.SubmitSearch>()
        action.method shouldBe YouTubeSearchSubmitMethod.ACCESSIBILITY_BUTTON
    }

    "YouTubePlayAutoResult failure leaves YouTube open at driver layer" {
        val selector = FakeYouTubeInAppSelector(selectSucceeds = false)
        val adapter = YouTubeMediaAdapter(FakeYouTubeRuntime()).also { it.detect() }
        val result = YouTubePlayAutoDriver(adapter, selector)
            .execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.youtubeLeftOpen shouldBe true
        result.searchOpened shouldBe true
        result.resultSelected shouldBe false
        adapter.dispatchCount shouldBe 1
    }
})
