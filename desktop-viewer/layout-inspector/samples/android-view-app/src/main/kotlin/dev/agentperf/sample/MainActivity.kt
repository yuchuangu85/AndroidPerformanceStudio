package com.androidperformancestudio.sample

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(48, 96, 48, 48)
                setBackgroundColor(Color.rgb(248, 250, 252))
                addView(label("AndroidPerfermanceStudio Sample", 28f))
                addView(label("Zero-code debug Agent is active.", 16f))
                addView(
                    label("Inspect this hierarchy from Desktop Viewer.", 14f).apply {
                        alpha = 0.72f
                    },
                )
            },
        )
    }

    private fun label(value: String, sizeSp: Float) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(Color.rgb(24, 32, 48))
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = 32
            }
        }
}
