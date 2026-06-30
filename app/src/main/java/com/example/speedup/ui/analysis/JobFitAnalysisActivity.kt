package com.example.speedup.ui.analysis

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.speedup.R
import com.example.speedup.data.repository.ScanResultCache
import com.example.speedup.databinding.ActivityJobFitAnalysisBinding
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
        val matchedList = intent.getStringArrayListExtra("SKILLS_MATCHED") ?: arrayListOf()
        val missingList = intent.getStringArrayListExtra("SKILLS_MISSING") ?: arrayListOf()
        val partialList = intent.getStringArrayListExtra("SKILLS_PARTIAL") ?: arrayListOf()

        binding.tvAnalysisTitle.text = jobTitle
        binding.tvAnalysisCompany.text = jobCompany
        binding.analysisScoreProgress.progress = fitScore
        binding.analysisScoreText.text = "${fitScore}%"

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

        binding.btnBack.setOnClickListener { finish() }

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

        populateExtractedFields()
        populateAccessibilityTree()

        binding.btnAnalysisSave.setOnClickListener {
            Toast.makeText(this, "Job saved to bookmarks", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun populateExtractedFields() {
        val fields = ScanResultCache.formFields
        val container = binding.containerDetectedFields
        val emptyView = binding.tvFormFieldsEmpty
        container.removeAllViews()

        if (fields.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }

        emptyView.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (field in fields) {
            val row = inflater.inflate(R.layout.item_detected_field, container, false)
            row.findViewById<TextView>(R.id.tv_field_label).text = field.label
            row.findViewById<TextView>(R.id.tv_field_type).text = field.fieldType
            val hintView = row.findViewById<TextView>(R.id.tv_field_hint)
            if (field.hint.isNotBlank() && field.hint != field.label) {
                hintView.text = field.hint
                hintView.visibility = View.VISIBLE
            } else {
                hintView.visibility = View.GONE
            }
            val positionView = row.findViewById<TextView>(R.id.tv_field_position)
            if (field.documentTopPx >= 0 || field.topPx >= 0) {
                positionView.text = buildString {
                    if (field.documentTopPx >= 0) {
                        append("page ").append(field.documentTopPx).append("px")
                    }
                    if (field.topPx >= 0) {
                        if (field.documentTopPx >= 0) append(" · ")
                        append("screen ").append(field.topPx).append("px")
                    }
                    if (field.heightPx > 0) append(" · h ").append(field.heightPx).append("px")
                }
                positionView.visibility = View.VISIBLE
            } else {
                positionView.visibility = View.GONE
            }
            container.addView(row)
        }
    }

    private fun populateAccessibilityTree() {
        val dump = ScanResultCache.treeDump
        val nodes = ScanResultCache.nodeCount
        val pkg = ScanResultCache.sourcePackage
        val fieldCount = ScanResultCache.formFields.size

        val pageMeta = when {
            ScanResultCache.pageNormalizedToTop -> "page coords normalized"
            ScanResultCache.scrollOffsetYAtCapture > 0 ->
                "scroll offset ${ScanResultCache.scrollOffsetYAtCapture}px"
            else -> null
        }
        binding.tvTreeMeta.text = when {
            dump.isBlank() -> "No accessibility tree cached from the last scan."
            pkg != null && pageMeta != null ->
                "$nodes nodes · $fieldCount inputs · $pkg · $pageMeta"
            pkg != null -> "$nodes nodes · $fieldCount inputs · $pkg"
            else -> "$nodes nodes · $fieldCount inputs"
        }
        binding.tvAccessibilityTree.text = dump.ifBlank {
            "Tap the floating widget on a job page to capture the screen tree."
        }
    }

    private fun addSkillTag(flowLayout: FlowLayout, text: String, isMatched: Boolean) {
        val textView = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(
                if (isMatched) resources.getColor(R.color.color_success, null)
                else resources.getColor(R.color.color_danger, null)
            )
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            val gd = GradientDrawable().apply {
                setColor(resources.getColor(R.color.surface_glass, null))
                cornerRadius = dpToPx(100).toFloat()
                setStroke(
                    dpToPx(1),
                    if (isMatched) resources.getColor(R.color.color_success, null)
                    else resources.getColor(R.color.color_danger, null)
                )
            }
            background = gd
        }
        flowLayout.addView(textView)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
