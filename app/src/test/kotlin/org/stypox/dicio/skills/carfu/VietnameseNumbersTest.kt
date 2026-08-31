package org.stypox.dicio.skills.carfu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class VietnameseNumbersTest : StringSpec({
    "parses digits" {
        VietnameseNumbers.parseInt("5") shouldBe 5L
        VietnameseNumbers.parseInt("10") shouldBe 10L
        VietnameseNumbers.parseInt("0912") shouldBe 912L
    }

    "parses Vietnamese number words" {
        VietnameseNumbers.parseInt("khong") shouldBe 0L
        VietnameseNumbers.parseInt("mot") shouldBe 1L
        VietnameseNumbers.parseInt("nam") shouldBe 5L
        VietnameseNumbers.parseInt("muoi") shouldBe 10L
        VietnameseNumbers.parseInt("muoi lam") shouldBe 15L
        VietnameseNumbers.parseInt("hai muoi") shouldBe 20L
        VietnameseNumbers.parseInt("hai muoi mot") shouldBe 21L
        VietnameseNumbers.parseInt("mot tram") shouldBe 100L
    }

    "parses duration from digits and words" {
        VietnameseNumbers.parseDurationMs("hen gio 5 phut") shouldBe 5 * 60_000L
        VietnameseNumbers.parseDurationMs("hen gio muoi phut") shouldBe 10 * 60_000L
        VietnameseNumbers.parseDurationMs("30 giay") shouldBe 30_000L
        VietnameseNumbers.parseDurationMs("1 gio") shouldBe 3_600_000L
        VietnameseNumbers.parseDurationMs("hen gio") shouldBe null
    }

    "parses safe two-operand arithmetic" {
        val add = VietnameseNumbers.parseArithmetic("tinh 5 cong 3")!!
        add.left shouldBe 5.0
        add.op shouldBe VietnameseNumbers.ArithmeticOp.ADD
        add.right shouldBe 3.0
        val mul = VietnameseNumbers.parseArithmetic("muoi nhan hai")!!
        mul.left shouldBe 10.0
        mul.op shouldBe VietnameseNumbers.ArithmeticOp.MUL
        mul.right shouldBe 2.0
        val div = VietnameseNumbers.parseArithmetic("100 chia 4")!!
        div.op shouldBe VietnameseNumbers.ArithmeticOp.DIV
        VietnameseNumbers.parseArithmetic("mo youtube").shouldBeNull()
    }
})
