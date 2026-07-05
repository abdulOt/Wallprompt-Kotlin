package com.ams.wallverse

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class CategoriesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private var categoryList = mutableListOf<Category>()
    private lateinit var connectivityReceiver: ConnectivityReceiver

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_categories, container, false)
        recyclerView = view.findViewById(R.id.categoryRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 1)
        recyclerView.adapter = CategoryAdapter(categoryList) { category ->
            openCategoryWallpapers(category.name)
        }

        // Register the receiver for connectivity changes
        connectivityReceiver = ConnectivityReceiver(object : ConnectivityReceiver.OnNetworkChangeListener {
            override fun onNetworkAvailable() {
                // Load categories from Firebase only if network is available
                loadCategoriesFromFirebase()
            }

            override fun onNetworkUnavailable() {
                showNoInternetDialog()
            }
        })
        val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        requireContext().registerReceiver(connectivityReceiver, intentFilter)

        return view
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadCategoriesFromFirebase() {
        FirebaseFirestore.getInstance()
            .collection("categories")
            .orderBy("position")
            .get()
            .addOnSuccessListener { result ->
                categoryList.clear()
                for (doc in result) {
                    val category = doc.toObject(Category::class.java)
                    categoryList.add(category)
                }
                recyclerView.adapter?.notifyDataSetChanged()
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Failed to load categories!!", Toast.LENGTH_SHORT).show()
            }
    }

    private fun openCategoryWallpapers(categoryName: String) {
        val intent = Intent(requireContext(), CategoryImagesActivity::class.java)
        intent.putExtra("category", categoryName)
        startActivity(intent)
    }

    private fun showNoInternetDialog() {
        // Show a message to the user if no internet connection is available
        Toast.makeText(requireContext(), "No internet connection!", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the receiver to prevent memory leaks
        requireContext().unregisterReceiver(connectivityReceiver)
    }
}
