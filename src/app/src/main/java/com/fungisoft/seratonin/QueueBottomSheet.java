package com.fungisoft.seratonin;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

/**
 * Bottom sheet dialog for displaying and managing the playback queue.
 */
public class QueueBottomSheet extends BottomSheetDialogFragment implements QueueAdapter.OnQueueItemClickListener {

    private RecyclerView recyclerView;
    private LinearLayout emptyView;
    private TextView queueCount;
    private ImageView saveBtn;
    private ImageView clearBtn;
    
    private QueueAdapter adapter;
    private ArrayList<MusicFiles> queue;
    private int currentIndex;
    private QueueDatabase queueDatabase;
    
    private OnQueueActionListener listener;

    public interface OnQueueActionListener {
        void onSongSelected(int position);
        void onQueueCleared();
        void onQueueChanged(ArrayList<MusicFiles> newQueue, int newCurrentIndex);
    }

    public static QueueBottomSheet newInstance() {
        return new QueueBottomSheet();
    }

    public void setQueue(ArrayList<MusicFiles> queue, int currentIndex) {
        this.queue = new ArrayList<>(queue); // Copy to avoid reference issues
        this.currentIndex = currentIndex;
        if (adapter != null) {
            adapter.updateQueue(this.queue);
            adapter.setCurrentPlayingIndex(currentIndex);
            updateEmptyView();
            updateQueueCount();
        }
    }

    public void setOnQueueActionListener(OnQueueActionListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.BottomSheetDialogTheme);
        queueDatabase = QueueDatabase.getInstance(requireContext());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(dialogInterface -> {
            BottomSheetDialog d = (BottomSheetDialog) dialogInterface;
            View bottomSheet = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
                
                // Set max height to 70% of screen
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                behavior.setMaxHeight((int) (screenHeight * 0.7));
            }
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_queue, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Apply window insets to respect status bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), insets.top, v.getPaddingRight(), 0);
            return windowInsets;
        });
        
        recyclerView = view.findViewById(R.id.queue_recycler_view);
        emptyView = view.findViewById(R.id.empty_queue_view);
        queueCount = view.findViewById(R.id.queue_count);
        saveBtn = view.findViewById(R.id.save_queue_btn);
        clearBtn = view.findViewById(R.id.clear_queue_btn);
        
        setupRecyclerView();
        setupButtons();
        updateEmptyView();
        updateQueueCount();
    }

    private void setupRecyclerView() {
        if (queue == null) {
            queue = new ArrayList<>();
        }
        
        adapter = new QueueAdapter(requireContext(), queue, this);
        adapter.setCurrentPlayingIndex(currentIndex);
        
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        
        // Setup drag and swipe
        ItemTouchHelper.Callback callback = new QueueAdapter.QueueItemTouchCallback(adapter);
        ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(recyclerView);
        adapter.setItemTouchHelper(touchHelper);
        
        // Scroll to current playing
        if (currentIndex >= 0 && currentIndex < queue.size()) {
            recyclerView.scrollToPosition(currentIndex);
        }
    }

    private void setupButtons() {
        saveBtn.setOnClickListener(v -> saveQueueToM3U());
        clearBtn.setOnClickListener(v -> confirmClearQueue());
    }

    private void saveQueueToM3U() {
        String musicFolderPath = FolderSelectionActivity.getMusicFolderPath(requireContext());
        if (musicFolderPath == null) {
            Toast.makeText(requireContext(), "No music folder set", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String savedPath = queueDatabase.exportToM3UWithTimestamp(musicFolderPath);
        if (savedPath != null) {
            Toast.makeText(requireContext(), "Queue saved to:\n" + savedPath, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(requireContext(), "Failed to save queue", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearQueue() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear Queue")
                .setMessage("Are you sure you want to clear the queue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    queue.clear();
                    adapter.notifyDataSetChanged();
                    queueDatabase.clearQueue();
                    updateEmptyView();
                    updateQueueCount();
                    
                    if (listener != null) {
                        listener.onQueueCleared();
                    }
                })
                .show();
    }

    private void updateEmptyView() {
        if (queue == null || queue.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }
    }

    private void updateQueueCount() {
        int count = queue != null ? queue.size() : 0;
        queueCount.setText("(" + count + " " + (count == 1 ? "song" : "songs") + ")");
    }

    @Override
    public void onItemClick(int position) {
        if (listener != null) {
            listener.onSongSelected(position);
        }
        adapter.setCurrentPlayingIndex(position);
        currentIndex = position;
        queueDatabase.saveCurrentIndex(position);
        dismiss();
    }

    @Override
    public void onRemoveClick(int position) {
        if (position >= 0 && position < queue.size()) {
            // Don't allow removing currently playing song
            if (position == currentIndex) {
                Toast.makeText(requireContext(), "Cannot remove currently playing song", Toast.LENGTH_SHORT).show();
                adapter.notifyItemChanged(position); // Reset swipe
                return;
            }
            
            adapter.removeItem(position);
            queueDatabase.removeFromQueue(position);
            
            // Update current index in database
            currentIndex = adapter.getCurrentPlayingIndex();
            queueDatabase.saveCurrentIndex(currentIndex);
            
            updateEmptyView();
            updateQueueCount();
            
            if (listener != null) {
                listener.onQueueChanged(queue, currentIndex);
            }
        }
    }

    @Override
    public void onItemMoved(int fromPosition, int toPosition) {
        // Update database
        queueDatabase.saveQueue(queue);
        
        // Update current index
        currentIndex = adapter.getCurrentPlayingIndex();
        queueDatabase.saveCurrentIndex(currentIndex);
        
        if (listener != null) {
            listener.onQueueChanged(queue, currentIndex);
        }
    }
}
