package org.stypox.dicio.youtubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class YouTubeVideoClickResolverTest : StringSpec({
    fun node(
        index: Int,
        parent: Int?,
        className: String,
        text: String = "",
        clickable: Boolean = false,
        viewId: String = "",
        editable: Boolean = false,
    ) = YouTubeNodeFact(
        className = className,
        text = text,
        clickable = clickable,
        viewId = viewId,
        editable = editable,
        index = index,
        parentIndex = parent,
    )

    "title node directly clickable" {
        val title = node(0, null, "android.widget.TextView", "Đừng Xa Em Đêm Nay - Hồ Hoàng Yến [Official 4K MV]", clickable = true)
        val resolved = YouTubeVideoClickResolver.resolve(title, listOf(title)).shouldNotBeNull()
        resolved.nodeIndex shouldBe 0
        resolved.depthFromTitle shouldBe 0
        resolved.titleClickable shouldBe true
        resolved.clickableClass shouldBe "android.widget.TextView"
    }

    "title node not clickable but parent clickable" {
        val parent = node(0, null, "android.widget.FrameLayout", clickable = true)
        val title = node(1, 0, "android.widget.TextView", "Đừng Xa Em Đêm Nay - Official MV", clickable = false)
        val resolved = YouTubeVideoClickResolver.resolve(title, listOf(parent, title)).shouldNotBeNull()
        resolved.nodeIndex shouldBe 0
        resolved.depthFromTitle shouldBe 1
        resolved.titleClickable shouldBe false
        resolved.clickableClass shouldBe "android.widget.FrameLayout"
    }

    "clickable ancestor 1 level up" {
        val row = node(0, null, "android.widget.LinearLayout", clickable = true)
        val title = node(1, 0, "android.widget.TextView", "Song title", clickable = false)
        val resolved = YouTubeVideoClickResolver.resolve(title, listOf(row, title)).shouldNotBeNull()
        resolved.depthFromTitle shouldBe 1
        resolved.nodeIndex shouldBe 0
    }

    "clickable ancestor multiple levels up" {
        val row = node(0, null, "android.widget.FrameLayout", clickable = true)
        val inner = node(1, 0, "android.widget.LinearLayout", clickable = false)
        val title = node(2, 1, "android.widget.TextView", "Song title", clickable = false)
        val resolved = YouTubeVideoClickResolver.resolve(title, listOf(row, inner, title)).shouldNotBeNull()
        resolved.depthFromTitle shouldBe 2
        resolved.nodeIndex shouldBe 0
        resolved.clickableClass shouldBe "android.widget.FrameLayout"
    }

    "no clickable ancestor yields no click" {
        val list = node(0, null, "androidx.recyclerview.widget.RecyclerView", clickable = false)
        val title = node(1, 0, "android.widget.TextView", "Song title", clickable = false)
        YouTubeVideoClickResolver.resolve(title, listOf(list, title)).shouldBeNull()
    }

    "does not click RecyclerView even if it is clickable" {
        val list = node(0, null, "androidx.recyclerview.widget.RecyclerView", clickable = true)
        val title = node(1, 0, "android.widget.TextView", "Song title", clickable = false)
        YouTubeVideoClickResolver.resolve(title, listOf(list, title)).shouldBeNull()
    }

    "does not click keyboard or search field ancestors" {
        val keyboard = node(0, null, "android.inputmethodservice.KeyboardView", clickable = true)
        val title = node(1, 0, "android.widget.TextView", "Song title", clickable = false)
        YouTubeVideoClickResolver.resolve(title, listOf(keyboard, title)).shouldBeNull()

        val edit = node(0, null, "android.widget.EditText", "Đừng Xa Em Đêm Nay", clickable = true, editable = true)
        YouTubeVideoClickResolver.resolve(edit, listOf(edit)).shouldBeNull()
    }

    "does not click navigation tabs" {
        val tab = node(0, null, "android.widget.FrameLayout", "Home", clickable = true)
        YouTubeVideoClickResolver.resolve(tab, listOf(tab)).shouldBeNull()
        val shorts = node(1, null, "android.widget.FrameLayout", "Shorts", clickable = true)
        YouTubeVideoClickResolver.resolve(shorts, listOf(shorts)).shouldBeNull()
    }
})
