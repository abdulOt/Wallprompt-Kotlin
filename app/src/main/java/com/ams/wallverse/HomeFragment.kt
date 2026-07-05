package com.ams.wallverse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.firestore.FirebaseFirestore

class HomeFragment : Fragment(), ConnectivityReceiver.OnNetworkChangeListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WallpaperAdapter
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private val wallpaperList = mutableListOf<WallpaperModel>()

    private var connectivityReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        // Initialize views
        recyclerView = view.findViewById(R.id.homeRecyclerView)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)

        // Setup layout
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)

        val spacingPx = (6 * resources.displayMetrics.density).toInt()
        recyclerView.addItemDecoration(GridSpacingItemDecoration(2, spacingPx, true))

        // Set item animation once
        val controller = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_animation_fall_down)
        recyclerView.layoutAnimation = controller

        // Adapter
        adapter = WallpaperAdapter(wallpaperList) { wallpaper ->
            openFullscreen(wallpaper)
        }
        recyclerView.adapter = adapter

        // Pull to refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadWallpapersFromFirebase()
        }

        // Bottom nav hide/show on scroll
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (dy > 5 || dy < -5) {
                    val hide = dy > 5
                    parentFragmentManager.setFragmentResult(
                        "scrollEvent",
                        Bundle().apply {
                            putBoolean("hideBottomNav", hide)
                        }
                    )
                }
            }
        })

        return view
    }

    override fun onResume() {
        super.onResume()
        // show spinner
        swipeRefreshLayout.isRefreshing = true
        // fetch & diff–update; updateWallpapersWithDiff() will scrollToPosition(0)
        if (isNetworkAvailable(requireContext())) {
            loadWallpapersFromFirebase()
        } else {
            Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
            swipeRefreshLayout.isRefreshing = false
        }
    }

    override fun onNetworkAvailable() {
        // Reload wallpapers when network is available
        loadWallpapersFromFirebase()
    }

    override fun onNetworkUnavailable() {
        // Show a message when network is unavailable
        Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
        swipeRefreshLayout.isRefreshing = false
    }

    private fun openFullscreen(wallpaper: WallpaperModel) {
        // build a BooleanArray from your list
        val isPremium = requireContext().getSharedPreferences("prefs", 0).getBoolean("is_premium", false)
        val boolArray = BooleanArray(wallpaperList.size) { i ->
            if (isPremium) false else wallpaperList[i].rewarded
        }

        val intent = Intent(requireContext(), FullscreenActivity::class.java).apply {
            putStringArrayListExtra(
                "imageList",
                ArrayList(wallpaperList.map { it.url })
            )
            putExtra("rewardedList", boolArray)            // <-- boolean[] extra
            putExtra("selectedPosition", wallpaperList.indexOf(wallpaper))
        }
        startActivity(intent)
    }

    private fun loadWallpapersFromFirebase() {
        FirebaseFirestore.getInstance()
            .collection("homepageImages")
            .get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.isEmpty) {
                    val newWallpapers = mutableListOf<WallpaperModel>()
                    for (document in snapshot) {
                        val url = document.getString("url")
                        val isRewarded = document.getBoolean("rewarded") ?: false
                        if (!url.isNullOrEmpty()) {
                            Log.d("HomeFragment", "Loaded wallpaper: $url")
                            newWallpapers.add(WallpaperModel(url, isRewarded))
                        }
                    }
                    newWallpapers.shuffle()
                    updateWallpapersWithDiff(newWallpapers)
                } else {
                    Log.w("HomeFragment", "No wallpapers found.")
                    swipeRefreshLayout.isRefreshing = false
                }
            }
            .addOnFailureListener { error ->
                swipeRefreshLayout.isRefreshing = false
                Toast.makeText(requireContext(), "Error: ${error.message}", Toast.LENGTH_SHORT).show()
                Log.e("HomeFragment", "Firestore error: ${error.message}")
            }
    }

    private fun updateWallpapersWithDiff(newWallpapers: List<WallpaperModel>) {
        val diffResult = DiffUtil.calculateDiff(WallpaperDiffCallback(wallpaperList, newWallpapers))
        wallpaperList.clear()
        wallpaperList.addAll(newWallpapers)
        diffResult.dispatchUpdatesTo(adapter)

        // Scroll to top
        recyclerView.scrollToPosition(0)

        // Trigger animation
        recyclerView.scheduleLayoutAnimation()

        // Stop refresh spinner
        swipeRefreshLayout.isRefreshing = false
    }

    class WallpaperDiffCallback(
        private val oldList: List<WallpaperModel>,
        private val newList: List<WallpaperModel>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition].url == newList[newItemPosition].url
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition] == newList[newItemPosition]
    }

    // Register receiver in onCreate
    override fun onStart() {
        super.onStart()
        if (connectivityReceiver == null) {
            connectivityReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (isNetworkAvailable(context)) {
                        onNetworkAvailable()
                    } else {
                        onNetworkUnavailable()
                    }
                }
            }
        }
        if (!isReceiverRegistered) {
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            // For SDK 33+, prefer ContextCompat.registerReceiver(context, receiver, filter, RECEIVER_NOT_EXPORTED)
            requireContext().registerReceiver(connectivityReceiver, filter)
            isReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (isReceiverRegistered) {
            runCatching { requireContext().unregisterReceiver(connectivityReceiver) }
            isReceiverRegistered = false
        }
        super.onStop()
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

}

