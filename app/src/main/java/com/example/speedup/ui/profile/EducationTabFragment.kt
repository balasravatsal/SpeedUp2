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
import com.example.speedup.databinding.FragmentEducationBinding

class EducationTabFragment : Fragment() {

    private var _binding: FragmentEducationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEducationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.education.observe(viewLifecycleOwner) { list ->
            binding.educationListContainer.removeAllViews()
            val inflater = LayoutInflater.from(context)

            for (edu in list) {
                val itemView = inflater.inflate(R.layout.item_profile_detail, binding.educationListContainer, false)
                itemView.findViewById<TextView>(R.id.tv_item_title).text = edu.degree
                itemView.findViewById<TextView>(R.id.tv_item_subtitle).text = edu.school
                itemView.findViewById<TextView>(R.id.tv_item_meta).text = edu.year
                itemView.findViewById<TextView>(R.id.tv_item_meta).visibility = View.VISIBLE
                
                // Add margins between cards
                val params = itemView.layoutParams as ViewGroup.MarginLayoutParams
                params.bottomMargin = dpToPx(12)
                itemView.layoutParams = params

                binding.educationListContainer.addView(itemView)
            }
        }

        binding.btnAddEducation.setOnClickListener {
            showAddEducationDialog()
        }
    }

    private fun showAddEducationDialog() {
        val builder = AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Add Education")

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_education, null)
        builder.setView(dialogView)

        val etDegree = dialogView.findViewById<EditText>(R.id.et_dialog_degree)
        val etSchool = dialogView.findViewById<EditText>(R.id.et_dialog_school)
        val etYear = dialogView.findViewById<EditText>(R.id.et_dialog_year)

        builder.setPositiveButton("Add") { dialog, _ ->
            val degree = etDegree.text.toString().trim()
            val school = etSchool.text.toString().trim()
            val year = etYear.text.toString().trim()

            if (degree.isNotEmpty() && school.isNotEmpty()) {
                viewModel.addEducation(degree, school, year.ifEmpty { "2024" })
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
