package com.example.speedup.ui.profile

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.speedup.R
import com.example.speedup.databinding.FragmentSkillsBinding

class SkillsTabFragment : Fragment() {

    private var _binding: FragmentSkillsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSkillsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            binding.flowSkills.removeAllViews()
            val inflater = LayoutInflater.from(context)

            for (skill in profile.skills) {
                val tagView = inflater.inflate(R.layout.item_skill_tag, binding.flowSkills, false) as TextView
                tagView.text = skill
                binding.flowSkills.addView(tagView)
            }
        }

        binding.btnAddSkill.setOnClickListener {
            showAddSkillDialog()
        }
    }

    private fun showAddSkillDialog() {
        val builder = AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Add Skill")

        val input = EditText(context).apply {
            hint = "Skill name (e.g. Kotlin)"
            setTextColor(resources.getColor(R.color.white, null))
            setHintTextColor(resources.getColor(R.color.text_muted, null))
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16))
        }
        builder.setView(input)

        builder.setPositiveButton("Add") { dialog, _ ->
            val skill = input.text.toString().trim()
            if (skill.isNotEmpty()) {
                viewModel.addSkill(skill)
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }

        builder.show()
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
