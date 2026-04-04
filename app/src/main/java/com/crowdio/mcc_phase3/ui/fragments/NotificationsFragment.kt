package com.crowdio.mcc_phase3.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crowdio.mcc_phase3.R
import com.crowdio.mcc_phase3.ui.adapters.NotificationAdapter
import com.crowdio.mcc_phase3.utils.NotificationStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class NotificationsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var unreadChip: Chip
    private lateinit var btnMarkRead: MaterialButton
    private lateinit var btnClearAll: MaterialButton
    private lateinit var adapter: NotificationAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_notifications, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.notifications_recycler_view)
        emptyState   = view.findViewById(R.id.empty_state)
        unreadChip   = view.findViewById(R.id.unread_chip)
        btnMarkRead  = view.findViewById(R.id.btn_mark_read)
        btnClearAll  = view.findViewById(R.id.btn_clear_all)

        adapter = NotificationAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        btnMarkRead.setOnClickListener {
            NotificationStore.markAllRead()
        }

        btnClearAll.setOnClickListener {
            NotificationStore.clearAll()
        }

        // Observe store
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotificationStore.items.collect { items ->
                    adapter.submitList(items)

                    val isEmpty = items.isEmpty()
                    recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
                    emptyState.visibility   = if (isEmpty) View.VISIBLE else View.GONE

                    val unread = items.count { !it.isRead }
                    if (unread > 0) {
                        unreadChip.text = "$unread unread"
                        unreadChip.visibility = View.VISIBLE
                    } else {
                        unreadChip.visibility = View.GONE
                    }
                }
            }
        }
    }
}
