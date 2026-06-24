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
import com.example.speedup.databinding.FragmentExperienceBinding

class ExperienceTabFragment : Fragment() {

    private var _binding: FragmentExperienceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExperienceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.experiences.observe(viewLifecycleOwner) { list ->
            binding.experienceListContainer.removeAllViews()
            val inflater = LayoutInflater.from(context)

            for (exp in list) {
                val itemView = inflater.inflate(R.layout.item_profile_detail, binding.experienceListContainer, false)
                itemView.findViewById<TextView>(R.id.tv_item_title).text = exp.title
                itemView.findViewById<TextView>(R.id.tv_item_subtitle).text = exp.company
                itemView.findViewById<TextView>(R.id.tv_item_meta).text = exp.duration
                itemView.findViewById<TextView>(R.id.tv_item_meta).visibility = View.VISIBLE
                
                // Add margins between cards
                val params = itemView.layoutParams as ViewGroup.MarginLayoutParams
                params.bottomMargin = dpToPx(12)
                itemView.layoutParams = params

                binding.experienceListContainer.addView(itemView)
            }
        }

        binding.btnAddExperience.setOnClickListener {
            showAddExperienceDialog()
        }
    }

    private fun showAddExperienceDialog() {
        val builder = AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Add Experience")

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_experience, null)
        builder.setView(dialogView)

        val etTitle = dialogView.findViewById<EditText>(R.id.et_dialog_title)
        val etCompany = dialogView.findViewById<EditText>(R.id.et_dialog_company)
        val etDuration = dialogView.findViewById<EditText>(R.id.et_dialog_duration)

        builder.setPositiveButton("Add") { dialog, _ ->
            val title = etTitle.text.toString().trim()
            val company = etCompany.text.toString().trim()
            val duration = etDuration.text.toString().trim()

            if (title.isNotEmpty() && company.isNotEmpty()) {
                viewModel.addExperience(title, company, duration.ifEmpty { "1 year" })
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
