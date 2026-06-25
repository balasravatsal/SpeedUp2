package com.example.speedup.ui.analysis

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.speedup.R
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.databinding.ActivityJobFitAnalysisBinding
import com.example.speedup.service.SpeedUpAccessibilityService
import com.example.speedup.ui.view.FlowLayout

class JobFitAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJobFitAnalysisBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJobFitAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val jobTitle = intent.getStringExtra("JOB_TITLE") ?: "Job Opening"
        val jobCompany = intent.getStringExtra("JOB_COMPANY") ?: "—"
        val fitScore = intent.getIntExtra("JOB_FIT_SCORE", 0)
        val fitLabel = intent.getStringExtra("FIT_LABEL") ?: ""
        val fitSubtitle = intent.getStringExtra("FIT_SUBTITLE") ?: ""
        val matchedList = intent.getStringArrayListExtra("SKILLS_MATCHED") ?: arrayListOf()
        val missingList = intent.getStringArrayListExtra("SKILLS_MISSING") ?: arrayListOf()
        val partialList = intent.getStringArrayListExtra("SKILLS_PARTIAL") ?: arrayListOf()

        binding.tvAnalysisTitle.text = jobTitle
        binding.tvAnalysisCompany.text = jobCompany
        binding.analysisScoreProgress.progress = fitScore
        binding.analysisScoreText.text = "${fitScore}%"

        // Update score label if present in layout
        try {
            val labelView = binding.root.findViewById<TextView>(
                resources.getIdentifier("tv_fit_label", "id", packageName)
            )
            labelView?.text = fitLabel.ifBlank {
                when {
                    fitScore >= 80 -> "Strong Match"
                    fitScore >= 50 -> "Partial Match"
                    fitScore >= 25 -> "Weak Match"
                    else -> "Poor Match"
                }
            }
        } catch (_: Exception) { }

        binding.btnBack.setOnClickListener {
            finish()
        }

        for (skill in matchedList) {
            addSkillTag(binding.flowMatchedSkills, skill, true)
        }

        for (skill in partialList) {
            addSkillTag(binding.flowMatchedSkills, "$skill (partial)", true)
        }

        for (skill in missingList) {
            addSkillTag(binding.flowMissingSkills, skill, false)
        }

        if (matchedList.isEmpty() && missingList.isEmpty() && partialList.isEmpty()) {
            addSkillTag(binding.flowMissingSkills, "Scroll JD into view for analysis", false)
        }

        // Apply action
        binding.btnAnalysisAutofill.setOnClickListener {
            val accessibility = SpeedUpAccessibilityService.instance
            if (accessibility == null) {
                Toast.makeText(this, "Enable Accessibility Service in Settings to auto-fill", Toast.LENGTH_LONG).show()
                finish()
                return@setOnClickListener
            }
            val appContext = applicationContext
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            Toast.makeText(this, "Returning to job form to auto-fill…", Toast.LENGTH_SHORT).show()
            finish()
            // performAutoFill blocks (sleeps + dispatches tap gestures whose
            // callbacks run on the main thread). It MUST run off the main thread,
            // otherwise the gesture latch never completes and the UI stalls/ANRs.
            mainHandler.postDelayed({
                Thread {
                    val repository = ProfileRepository(appContext)
                    val result = accessibility.performAutoFill(repository)
                    val message = when {
                        result.filledCount > 0 -> "Auto-filled ${result.filledCount} field(s) from your profile"
                        result.totalDetected == 0 -> "No form fields found. Open a job application form first."
                        else -> "Found ${result.fillable.size} fields but couldn't fill them. Tap a field and retry."
                    }
                    mainHandler.post {
                        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }, 500)
        }

        binding.btnAnalysisSave.setOnClickListener {
            Toast.makeText(this, "Job saved to bookmarks", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun addSkillTag(flowLayout: FlowLayout, text: String, isMatched: Boolean) {
        val textView = TextView(this).apply {
            this.text = text
            textSize = 12f // in sp by default
            setTextColor(if (isMatched) resources.getColor(R.color.color_success, null) else resources.getColor(R.color.color_danger, null))
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            
            // Build custom border drawable dynamically
            val gd = GradientDrawable().apply {
                setColor(resources.getColor(R.color.surface_glass, null))
                cornerRadius = dpToPx(100).toFloat()
                setStroke(
                    dpToPx(1), 
                    if (isMatched) resources.getColor(R.color.color_success, null) else resources.getColor(R.color.color_danger, null)
                )
            }
            background = gd
        }
        flowLayout.addView(textView)
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
