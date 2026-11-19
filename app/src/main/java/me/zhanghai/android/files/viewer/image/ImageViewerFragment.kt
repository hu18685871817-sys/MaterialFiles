/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.dispose
import coil.load
import com.google.android.material.card.MaterialCardView
import dev.chrisbanes.insetter.applySystemWindowInsetsToPadding
import java.io.IOException
import java.util.Locale
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ImageViewerFragmentBinding
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.ui.DepthPageTransformer
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableListParceler
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.createSendImageIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.mediumAnimTime
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.withChooser
import me.zhanghai.android.systemuihelper.SystemUiHelper

class ImageViewerFragment : Fragment(), ConfirmDeleteDialogFragment.Listener {
    private val args by args<Args>()
    private val argsPaths by lazy { args.intent.extraPathList }

    private lateinit var paths: MutableList<Path>
    private var initialPosition: Int = 0

    private lateinit var binding: ImageViewerFragmentBinding

    private lateinit var systemUiHelper: SystemUiHelper

    private lateinit var adapter: ImageViewerAdapter
    private lateinit var thumbnailAdapter: ThumbnailStripAdapter
    private lateinit var thumbnailLayoutManager: LinearLayoutManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedPaths = savedInstanceState?.getState<State>()?.paths
        if (savedPaths != null) {
            paths = savedPaths.toMutableList()
            initialPosition = args.position.coerceIn(0, (paths.size - 1).coerceAtLeast(0))
        } else {
            val rawPaths = argsPaths.toMutableList()
            initialPosition = args.position
            // If launched with a single image path, automatically discover all sibling images in the same folder
            if (rawPaths.size == 1) {
                val singlePath = rawPaths[0]
                val localFile = runCatching { singlePath.toFile() }.getOrNull()
                val parentDir = localFile?.parentFile
                if (localFile != null && parentDir != null && parentDir.isDirectory) {
                    val siblingFiles = parentDir.listFiles()?.filter { f ->
                        f.isFile && isImageExtension(f.extension)
                    }?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
                    if (siblingFiles.size > 1) {
                        rawPaths.clear()
                        rawPaths.addAll(siblingFiles.map { Paths.get(it.absolutePath) })
                        val foundIdx = siblingFiles.indexOfFirst { it.absolutePath == localFile.absolutePath }
                        if (foundIdx >= 0) {
                            initialPosition = foundIdx
                        }
                    }
                }
            }
            paths = rawPaths
        }

        setHasOptionsMenu(true)
    }

    private fun isImageExtension(ext: String): Boolean {
        return ext.lowercase(Locale.ROOT) in setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg"
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        ImageViewerFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (paths.isEmpty()) {
            finish()
            return
        }

        val activity = activity as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        activity.supportActionBar!!.setDisplayShowTitleEnabled(false)
        activity.window.statusBarColor = Color.TRANSPARENT
        binding.appBarLayout.applySystemWindowInsetsToPadding(left = true, top = true, right = true)
        binding.bottomThumbnailBar.applySystemWindowInsetsToPadding(left = true, right = true, bottom = true)

        systemUiHelper = SystemUiHelper(
            activity, SystemUiHelper.LEVEL_IMMERSIVE, SystemUiHelper.FLAG_IMMERSIVE_STICKY
        ) { visible: Boolean ->
            binding.appBarLayout.animate()
                .alpha(if (visible) 1f else 0f)
                .translationY(if (visible) 0f else -binding.appBarLayout.bottom.toFloat())
                .setDuration(mediumAnimTime.toLong())
                .setInterpolator(FastOutSlowInInterpolator())
                .start()

            binding.bottomThumbnailBar.animate()
                .alpha(if (visible) 1f else 0f)
                .translationY(if (visible) 0f else binding.bottomThumbnailBar.height.toFloat())
                .setDuration(mediumAnimTime.toLong())
                .setInterpolator(FastOutSlowInInterpolator())
                .withStartAction {
                    if (visible) binding.bottomThumbnailBar.visibility = View.VISIBLE
                }
                .withEndAction {
                    if (!visible) binding.bottomThumbnailBar.visibility = View.GONE
                }
                .start()
        }
        systemUiHelper.show()

        adapter = ImageViewerAdapter(viewLifecycleOwner) { systemUiHelper.toggle() }.apply {
            replace(paths)
        }

        thumbnailLayoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        thumbnailAdapter = ThumbnailStripAdapter(paths, initialPosition) { clickedPos ->
            if (clickedPos in paths.indices && binding.viewPager.currentItem != clickedPos) {
                binding.viewPager.setCurrentItem(clickedPos, false)
            }
        }
        binding.thumbnailRecyclerView.apply {
            layoutManager = thumbnailLayoutManager
            adapter = thumbnailAdapter
        }

        binding.viewPager.apply {
            offscreenPageLimit = 1
            adapter = this@ImageViewerFragment.adapter
            if (savedInstanceState == null) {
                setCurrentItem(initialPosition.coerceIn(0, paths.lastIndex), false)
            }
            setPageTransformer(DepthPageTransformer)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    syncBottomThumbnailSelection(position)
                }
            })
        }

        syncBottomThumbnailSelection(binding.viewPager.currentItem)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        if (paths.isEmpty()) {
            return
        }

        syncBottomThumbnailSelection(binding.viewPager.currentItem)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putState(State(paths))
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        inflater.inflate(R.menu.image_viewer, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            R.id.action_share -> {
                share()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun confirmDelete() {
        ConfirmDeleteDialogFragment.show(currentPath, this)
    }

    override fun delete(path: Path) {
        try {
            path.delete()
        } catch (e: IOException) {
            e.printStackTrace()
            showToast(e.toString())
            return
        }
        paths.removeAll(listOf(path))
        if (paths.isEmpty()) {
            finish()
            return
        }
        adapter.replace(paths)
        if (binding.viewPager.currentItem > paths.lastIndex) {
            binding.viewPager.currentItem = paths.lastIndex
        }
        thumbnailAdapter.updatePaths(paths, binding.viewPager.currentItem)
        syncBottomThumbnailSelection(binding.viewPager.currentItem)
        binding.viewPager.doOnPreDraw { binding.viewPager.requestTransform() }
    }

    private fun syncBottomThumbnailSelection(position: Int) {
        if (paths.isEmpty() || position !in paths.indices) return
        if (::thumbnailAdapter.isInitialized) {
            thumbnailAdapter.setSelectedPosition(position)
            binding.thumbnailRecyclerView.post {
                runCatching {
                    binding.thumbnailRecyclerView.smoothScrollToPosition(position)
                }
            }
        }
    }

    private fun share() {
        val path = currentPath
        val intent = path.fileProviderUri.createSendImageIntent()
            .apply { extraPath = path }
            .withChooser()
        startActivitySafe(intent)
    }

    private val currentPath: Path
        get() = paths[binding.viewPager.currentItem]

    /**
     * Adapter rendering the pure horizontal image thumbnail strip at the bottom of the Image Viewer.
     */
    private class ThumbnailStripAdapter(
        private var items: List<Path>,
        private var selectedPosition: Int,
        private val onThumbnailClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ThumbnailStripAdapter.ThumbViewHolder>() {

        fun updatePaths(newItems: List<Path>, newSelected: Int) {
            items = newItems.toList()
            selectedPosition = newSelected
            notifyDataSetChanged()
        }

        fun setSelectedPosition(newPos: Int) {
            if (newPos == selectedPosition) return
            val oldPos = selectedPosition
            selectedPosition = newPos
            if (oldPos in items.indices) notifyItemChanged(oldPos)
            if (newPos in items.indices) notifyItemChanged(newPos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            val card = MaterialCardView(ctx).apply {
                radius = dp(10).toFloat()
                cardElevation = dp(2).toFloat()
                setCardBackgroundColor(Color.parseColor("#1E293B"))
                layoutParams = RecyclerView.LayoutParams(dp(56), dp(56)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
            }

            val imageView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            card.addView(imageView)
            return ThumbViewHolder(card, imageView)
        }

        override fun onBindViewHolder(holder: ThumbViewHolder, position: Int) {
            val path = items[position]
            val isSelected = (position == selectedPosition)
            val density = holder.card.context.resources.displayMetrics.density
            fun dp(v: Float): Int = (v * density).toInt()

            holder.card.strokeWidth = if (isSelected) dp(2.5f) else dp(1f)
            holder.card.strokeColor = if (isSelected) {
                Color.parseColor("#38BDF8")
            } else {
                Color.parseColor("#475569")
            }
            holder.card.alpha = if (isSelected) 1.0f else 0.58f
            holder.card.scaleX = if (isSelected) 1.06f else 0.94f
            holder.card.scaleY = if (isSelected) 1.06f else 0.94f

            holder.imageView.dispose()
            val attrs = runCatching { path.readAttributes(BasicFileAttributes::class.java) }.getOrNull()
            if (attrs != null) {
                holder.imageView.load(path to attrs) {
                    crossfade(true)
                }
            } else {
                holder.imageView.load(path.fileProviderUri) {
                    crossfade(true)
                }
            }

            holder.card.setOnClickListener {
                val adapterPos = holder.bindingAdapterPosition
                if (adapterPos != RecyclerView.NO_POSITION) {
                    onThumbnailClick(adapterPos)
                }
            }
        }

        override fun onViewRecycled(holder: ThumbViewHolder) {
            super.onViewRecycled(holder)
            holder.imageView.dispose()
        }

        override fun getItemCount(): Int = items.size

        class ThumbViewHolder(
            val card: MaterialCardView,
            val imageView: ImageView
        ) : RecyclerView.ViewHolder(card)
    }

    @Parcelize
    class Args(val intent: Intent, val position: Int) : ParcelableArgs

    @Parcelize
    private class State(val paths: @WriteWith<ParcelableListParceler> List<Path>) : ParcelableState
}
