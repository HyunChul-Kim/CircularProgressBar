package com.chul.circularprogressbar.util

object MathUtils {

    fun constrain(amount: Int, low: Int, high: Int): Int {
        return when {
            amount < low -> low
            amount > high -> high
            else -> amount
        }
    }
}