package org.stypox.dicio.youtubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class YouTubePlayAutoResultClickTest : StringSpec({
    val song = "Đừng Xa Em Đêm Nay"
    val official = "$song - Hồ Hoàng Yến [Official 4K MV]"

    fun node(
        index: Int,
        parent: Int?,
        className: String,
        text: String = "",
        clickable: Boolean = false,
        viewId: String = "",
        editable: Boolean = false,
        focused: Boolean = false,
        contentDescription: String = "",
    ) = YouTubeNodeFact(
        className = className,
        text = text,
        contentDescription = contentDescription,
        viewId = viewId,
        clickable = clickable,
        focused = focused,
        editable = editable,
        index = index,
        parentIndex = parent,
    )

    fun submittedResults(nodes: List<YouTubeNodeFact>) = YouTubeUiFacts(
        query = song,
        submitted = true,
        nodes = nodes,
    )

    fun sessionAtResults(nodes: List<YouTubeNodeFact>): Pair<YouTubePlayAutoSession, YouTubePlayAutoAction> {
        val session = YouTubePlayAutoSession(song)
        val searchPage = YouTubeUiFacts(
            query = song,
            submitted = false,
            nodes = listOf(
                node(
                    0,
                    null,
                    "android.widget.EditText",
                    song,
                    editable = true,
                    focused = true,
                ),
            ),
        )
        session.onUi(searchPage).shouldBeInstanceOf<YouTubePlayAutoAction.SubmitSearch>()
        val action = session.onUi(submittedResults(nodes))
        return session to action
    }

    fun carfuResultTree(
        titleClickable: Boolean = false,
        rowClickable: Boolean = true,
        extraRows: List<YouTubeNodeFact> = emptyList(),
    ): List<YouTubeNodeFact> {
        val base = listOf(
            node(0, null, "android.widget.FrameLayout"),
            node(1, 0, "android.widget.EditText", song, editable = true, focused = false),
            node(2, 0, "android.widget.Button", "All", clickable = true),
            node(3, 0, "android.widget.TextView", "Home", clickable = true),
            node(4, 0, "android.widget.TextView", "Shorts", clickable = true),
            node(5, 0, "androidx.recyclerview.widget.RecyclerView"),
            node(6, 5, "android.widget.FrameLayout", clickable = rowClickable),
            node(7, 6, "android.widget.ImageView", contentDescription = "thumbnail"),
            node(8, 6, "android.widget.TextView", official, clickable = titleClickable),
            node(9, 6, "android.widget.TextView", "Hồ Hoàng Yến", viewId = "com.google.android.youtube:id/channel_name"),
            node(10, 5, "android.widget.FrameLayout", clickable = true),
            node(11, 10, "android.widget.TextView", "$song remix"),
        )
        return base + extraRows
    }

    "strong query-prefix title match selects Hồ Hoàng Yến Official 4K MV" {
        val facts = submittedResults(carfuResultTree())
        val best = YouTubeSearchUiClassifier.candidateResultEntries(facts).maxBy { it.score }
        best.title shouldBe official
        best.score shouldBe 100
        YouTubeTitleMatcher.score(official, song) shouldBe 100
        YouTubeSearchUiClassifier.isSuggestionText(official, song) shouldBe false
    }

    "remix and karaoke are penalized when the query did not request them" {
        val officialScore = YouTubeTitleMatcher.score(official, song) -
            YouTubeSearchUiClassifier.penalizeUnwantedCompletion(official, song)
        val remixScore = YouTubeTitleMatcher.score("$song remix", song) -
            YouTubeSearchUiClassifier.penalizeUnwantedCompletion("$song remix", song)
        val karaokeScore = YouTubeTitleMatcher.score("$song karaoke", song) -
            YouTubeSearchUiClassifier.penalizeUnwantedCompletion("$song karaoke", song)
        officialScore shouldBeGreaterThan remixScore
        officialScore shouldBeGreaterThan karaokeScore
        remixScore shouldBeLessThan 80
        karaokeScore shouldBeLessThan 80
    }

    "title node not clickable uses parent row clickable ancestor" {
        val (session, action) = sessionAtResults(carfuResultTree(titleClickable = false, rowClickable = true))
        val click = action.shouldBeInstanceOf<YouTubePlayAutoAction.ClickVideo>()
        click.title shouldBe official
        click.titleClickable shouldBe false
        click.ancestorDepth shouldBe 1
        click.clickableAncestorClass shouldBe "android.widget.FrameLayout"
        click.clickIndex shouldBe 6
        session.selectCount shouldBe 1
        session.diagnostics.stage shouldBe YouTubePlayAutoStage.SELECT_REQUESTED
        session.diagnostics.resultsPageDetected shouldBe true
        session.diagnostics.parentDepthUsed shouldBe 1
        session.onClickResult(true)
        session.diagnostics.resultSelected shouldBe true
        session.diagnostics.actionClickAttempted shouldBe true
        session.diagnostics.actionClickReturned shouldBe true
        session.diagnostics.stage shouldBe YouTubePlayAutoStage.RESULT_SELECTED
        session.diagnostics.format() shouldContain "ACTION_CLICK returned: true"
    }

    "title node directly clickable is clicked at depth 0" {
        val title = node(0, null, "android.widget.TextView", official, clickable = true)
        val (session, action) = sessionAtResults(
            listOf(
                node(1, null, "android.widget.Button", "All", clickable = true),
                title,
            ),
        )
        val click = action.shouldBeInstanceOf<YouTubePlayAutoAction.ClickVideo>()
        click.titleClickable shouldBe true
        click.ancestorDepth shouldBe 0
        click.clickIndex shouldBe 0
        session.selectCount shouldBe 1
    }

    "clickable ancestor multiple levels up is used" {
        val nodes = listOf(
            node(0, null, "android.widget.Button", "All", clickable = true),
            node(1, null, "android.widget.FrameLayout", clickable = true),
            node(2, 1, "android.widget.LinearLayout", clickable = false),
            node(3, 2, "android.widget.LinearLayout", clickable = false),
            node(4, 3, "android.widget.TextView", official, clickable = false),
        )
        val (_, action) = sessionAtResults(nodes)
        val click = action.shouldBeInstanceOf<YouTubePlayAutoAction.ClickVideo>()
        click.ancestorDepth shouldBe 3
        click.clickIndex shouldBe 1
        click.clickableAncestorClass shouldBe "android.widget.FrameLayout"
    }

    "no clickable ancestor produces no click" {
        val nodes = listOf(
            node(0, null, "android.widget.Button", "All", clickable = true),
            node(1, null, "androidx.recyclerview.widget.RecyclerView"),
            node(2, 1, "android.widget.TextView", official, clickable = false),
        )
        val (session, action) = sessionAtResults(nodes)
        val fail = action.shouldBeInstanceOf<YouTubePlayAutoAction.Fail>()
        fail.reason shouldBe "no_clickable_ancestor"
        session.selectCount shouldBe 0
        session.diagnostics.resultSelected shouldBe false
        session.diagnostics.failureReason shouldBe "no_clickable_ancestor"
        session.diagnostics.bestMatchedTitle shouldBe official
    }

    "duplicate accessibility events produce exactly one video click" {
        val (session, first) = sessionAtResults(carfuResultTree())
        first.shouldBeInstanceOf<YouTubePlayAutoAction.ClickVideo>()
        repeat(8) {
            session.onUi(submittedResults(carfuResultTree())) shouldBe YouTubePlayAutoAction.None
        }
        session.selectCount shouldBe 1
        session.diagnostics.selectAttemptCount shouldBe 1
        session.onClickResult(true)
        session.onUi(submittedResults(carfuResultTree())) shouldBe YouTubePlayAutoAction.None
        session.selectCount shouldBe 1
    }

    "result navigation and channel nodes are ignored" {
        val facts = submittedResults(carfuResultTree())
        val titles = YouTubeSearchUiClassifier.candidateResultEntries(facts).map { it.title }
        titles.contains("Home") shouldBe false
        titles.contains("Shorts") shouldBe false
        titles.contains("All") shouldBe false
        titles.contains("Hồ Hoàng Yến") shouldBe false
        titles.contains(official) shouldBe true
        val (session, action) = sessionAtResults(carfuResultTree())
        val click = action.shouldBeInstanceOf<YouTubePlayAutoAction.ClickVideo>()
        click.title shouldBe official
        session.selectCount shouldBe 1
    }

    "exactly one selected video per request even after click success events" {
        val (session, action) = sessionAtResults(carfuResultTree())
        action.shouldBeInstanceOf<YouTubePlayAutoAction.ClickVideo>()
        session.onClickResult(true)
        session.diagnostics.resultSelected shouldBe true
        repeat(3) {
            session.onUi(submittedResults(carfuResultTree())) shouldBe YouTubePlayAutoAction.None
        }
        session.selectCount shouldBe 1
        session.diagnostics.selectAttemptCount shouldBe 1
    }

    "failed ACTION_CLICK does not claim result selected and does not retry click" {
        val (session, action) = sessionAtResults(carfuResultTree())
        action.shouldBeInstanceOf<YouTubePlayAutoAction.ClickVideo>()
        val fail = session.onClickResult(false).shouldBeInstanceOf<YouTubePlayAutoAction.Fail>()
        fail.reason shouldBe "click_failed"
        session.diagnostics.resultSelected shouldBe false
        session.diagnostics.actionClickReturned shouldBe false
        session.onUi(submittedResults(carfuResultTree())) shouldBe YouTubePlayAutoAction.None
        session.selectCount shouldBe 1
    }

    "search submit 4.8.1 still happens before any result click" {
        val session = YouTubePlayAutoSession(song)
        val searchPage = YouTubeUiFacts(
            query = song,
            submitted = false,
            nodes = listOf(
                node(0, null, "android.widget.EditText", song, editable = true, focused = true),
                node(1, null, "android.widget.TextView", "$song remix", clickable = true),
            ),
        )
        session.onUi(searchPage).shouldBeInstanceOf<YouTubePlayAutoAction.SubmitSearch>()
        session.selectCount shouldBe 0
        session.submitCount shouldBe 1
        session.onUi(searchPage.copy(submitted = true)) shouldBe YouTubePlayAutoAction.Wait
        session.selectCount shouldBe 0
    }
})
