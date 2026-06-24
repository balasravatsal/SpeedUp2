package com.example.speedup.ui.profile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.speedup.databinding.FragmentResumeBinding

class ResumeTabFragment : Fragment() {

    private var _binding: FragmentResumeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by activityViewModels()

    private val resumePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val fileName = uri.lastPathSegment ?: "resume.pdf"
                viewModel.updateResume(fileName, uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResumeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            if (profile.resumeFileName.isNotEmpty()) {
                binding.tvResumeName.text = profile.resumeFileName
                binding.tvResumeMeta.text = "Stored locally · Ready for upload"
                binding.resumeCard.visibility = View.VISIBLE
            } else {
                binding.resumeCard.visibility = View.GONE
            }
        }

        binding.btnUploadResume.setOnClickListener { pickResume() }
    }

    private fun pickResume() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        resumePicker.launch(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
