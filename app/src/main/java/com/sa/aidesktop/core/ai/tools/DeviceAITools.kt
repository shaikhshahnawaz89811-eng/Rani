package com.sa.aidesktop.core.ai.tools

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.sa.aidesktop.core.ai.AIError
import com.sa.aidesktop.core.ai.AIResult
import com.sa.aidesktop.core.ai.AITool
import com.sa.aidesktop.core.ai.ToolResult
import com.sa.aidesktop.core.ai.ToolRisk
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class CalculatorTool : AITool {
    override val id = "calculator.calculate"
    override val description = "Calculate a real arithmetic expression using +, -, *, / and parentheses."
    override val risk = ToolRisk.READ_ONLY
    override val parameterHints = mapOf("expression" to "Arithmetic expression, for example 25*4 or (12+8)/2")

    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> {
        val expression = input["expression"]?.trim().orEmpty()
        if (expression.isBlank()) return AIResult.Failure(AIError.InvalidRequest("expression is required"))
        return try {
            val value = ArithmeticParser(expression).parse()
            val output = if (value.isFinite()) formatNumber(value)
            else throw IllegalArgumentException("Result is not finite.")
            AIResult.Success(ToolResult("$expression = $output"))
        } catch (e: IllegalArgumentException) {
            AIResult.Failure(AIError.InvalidRequest("Invalid arithmetic expression: ${e.message}"))
        }
    }

    private fun formatNumber(value: Double): String {
        if (abs(value - value.toLong()) < 1e-10) return value.toLong().toString()
        return String.format(Locale.US, "%.10f", value).trimEnd('0').trimEnd('.')
    }
}

class DeviceTimeTool : AITool {
    override val id = "device.time"
    override val description = "Read the device's current local time."
    override val risk = ToolRisk.READ_ONLY
    override val parameterHints = emptyMap<String, String>()

    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> =
        AIResult.Success(
            ToolResult("Current device time: ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}")
        )
}

class DeviceDateTool : AITool {
    override val id = "device.date"
    override val description = "Read the device's current local date."
    override val risk = ToolRisk.READ_ONLY
    override val parameterHints = emptyMap<String, String>()

    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> =
        AIResult.Success(
            ToolResult("Current device date: ${LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)}")
        )
}

class DeviceBatteryTool(private val context: Context) : AITool {
    override val id = "device.battery"
    override val description = "Read the Android device's real battery percentage and charging state."
    override val risk = ToolRisk.READ_ONLY
    override val parameterHints = emptyMap<String, String>()

    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return AIResult.Failure(AIError.Execution("Android BatteryManager is unavailable."))
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (level !in 0..100) return AIResult.Failure(AIError.Execution("Android did not return a valid battery percentage."))
        return AIResult.Success(
            ToolResult("Battery: $level% (${if (charging) "charging" else "not charging"})")
        )
    }
}

private class ArithmeticParser(private val source: String) {
    private var index = 0

    fun parse(): Double {
        val value = parseExpression()
        skipSpaces()
        if (index != source.length) throw IllegalArgumentException("unexpected '${source[index]}'")
        return value
    }

    private fun parseExpression(): Double {
        var value = parseTerm()
        while (true) {
            skipSpaces()
            if (match('+')) value += parseTerm()
            else if (match('-')) value -= parseTerm()
            else return value
        }
    }

    private fun parseTerm(): Double {
        var value = parseFactor()
        while (true) {
            skipSpaces()
            if (match('*')) value *= parseFactor()
            else if (match('/')) {
                val divisor = parseFactor()
                if (divisor == 0.0) throw IllegalArgumentException("division by zero")
                value /= divisor
            } else return value
        }
    }

    private fun parseFactor(): Double {
        skipSpaces()
        if (match('+')) return parseFactor()
        if (match('-')) return -parseFactor()
        if (match('(')) {
            val value = parseExpression()
            skipSpaces()
            if (!match(')')) throw IllegalArgumentException("missing ')'")
            return value
        }
        val start = index
        var dotSeen = false
        while (index < source.length) {
            val c = source[index]
            if (c.isDigit()) {
                index++
            } else if (c == '.' && !dotSeen) {
                dotSeen = true
                index++
            } else {
                break
            }
        }
        if (start == index) throw IllegalArgumentException("number expected")
        return source.substring(start, index).toDoubleOrNull()
            ?: throw IllegalArgumentException("invalid number")
    }

    private fun match(expected: Char): Boolean {
        if (index < source.length && source[index] == expected) {
            index++
            return true
        }
        return false
    }

    private fun skipSpaces() {
        while (index < source.length && source[index].isWhitespace()) index++
    }
}
