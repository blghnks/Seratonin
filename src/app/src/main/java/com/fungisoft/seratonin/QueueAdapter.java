package com.fungisoft.seratonin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Adapter for the queue bottom sheet RecyclerView.
 * Supports drag-to-reorder and swipe-to-remove.
 */
public class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.QueueViewHolder> {

    private Context context;
    private ArrayList<MusicFiles> queue;
    private int currentPlayingIndex = -1;
    private OnQueueItemClickListener listener;
    private ItemTouchHelper itemTouchHelper;

    public interface OnQueueItemClickListener {
        void onItemClick(int position);
        void onRemoveClick(int position);
        void onItemMoved(int fromPosition, int toPosition);
    }

    public QueueAdapter(Context context, ArrayList<MusicFiles> queue, OnQueueItemClickListener listener) {
        this.context = context;
        this.queue = queue;
        this.listener = listener;
    }

    public void setItemTouchHelper(ItemTouchHelper touchHelper) {
        this.itemTouchHelper = touchHelper;
    }

    @NonNull
    @Override
    public QueueViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_queue, parent, false);
        return new QueueViewHolder(view);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull QueueViewHolder holder, int position) {
        MusicFiles song = queue.get(position);
        
        // Set position number (1-indexed)
        holder.queuePosition.setText(String.valueOf(position + 1));
        
        // Set song info
        holder.songTitle.setText(song.getTitle() != null ? song.getTitle() : "Unknown");
        holder.songArtist.setText(song.getArtist() != null ? song.getArtist() : "Unknown Artist");
        
        // Show/hide now playing indicator
        if (position == currentPlayingIndex) {
            holder.nowPlayingIndicator.setVisibility(View.VISIBLE);
            holder.songTitle.setTextColor(context.getResources().getColor(R.color.accent_purple, null));
        } else {
            holder.nowPlayingIndicator.setVisibility(View.GONE);
            holder.songTitle.setTextColor(context.getResources().getColor(R.color.text_primary_dark, null));
        }
        
        // Click to play
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(holder.getBindingAdapterPosition());
            }
        });
        
        // Remove button
        holder.removeButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRemoveClick(holder.getBindingAdapterPosition());
            }
        });
        
        // Drag handle touch listener
        holder.dragHandle.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (itemTouchHelper != null) {
                    itemTouchHelper.startDrag(holder);
                }
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return queue != null ? queue.size() : 0;
    }

    public void setCurrentPlayingIndex(int index) {
        int oldIndex = currentPlayingIndex;
        currentPlayingIndex = index;
        if (oldIndex >= 0 && oldIndex < getItemCount()) {
            notifyItemChanged(oldIndex);
        }
        if (currentPlayingIndex >= 0 && currentPlayingIndex < getItemCount()) {
            notifyItemChanged(currentPlayingIndex);
        }
    }

    public int getCurrentPlayingIndex() {
        return currentPlayingIndex;
    }

    public void updateQueue(ArrayList<MusicFiles> newQueue) {
        this.queue = newQueue;
        notifyDataSetChanged();
    }

    public void moveItem(int fromPosition, int toPosition) {
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(queue, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(queue, i, i - 1);
            }
        }
        
        // Update current playing index if needed
        if (currentPlayingIndex == fromPosition) {
            currentPlayingIndex = toPosition;
        } else if (fromPosition < currentPlayingIndex && toPosition >= currentPlayingIndex) {
            currentPlayingIndex--;
        } else if (fromPosition > currentPlayingIndex && toPosition <= currentPlayingIndex) {
            currentPlayingIndex++;
        }
        
        notifyItemMoved(fromPosition, toPosition);
        
        // Notify listener to persist the change
        if (listener != null) {
            listener.onItemMoved(fromPosition, toPosition);
        }
    }

    public void removeItem(int position) {
        if (position >= 0 && position < queue.size()) {
            queue.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, queue.size() - position);
            
            // Update current playing index
            if (position < currentPlayingIndex) {
                currentPlayingIndex--;
            } else if (position == currentPlayingIndex) {
                // Current song was removed
                currentPlayingIndex = -1;
            }
        }
    }

    static class QueueViewHolder extends RecyclerView.ViewHolder {
        ImageView dragHandle;
        TextView queuePosition;
        TextView songTitle;
        TextView songArtist;
        ImageView nowPlayingIndicator;
        ImageView removeButton;

        public QueueViewHolder(@NonNull View itemView) {
            super(itemView);
            dragHandle = itemView.findViewById(R.id.drag_handle);
            queuePosition = itemView.findViewById(R.id.queue_position);
            songTitle = itemView.findViewById(R.id.queue_song_title);
            songArtist = itemView.findViewById(R.id.queue_song_artist);
            nowPlayingIndicator = itemView.findViewById(R.id.now_playing_indicator);
            removeButton = itemView.findViewById(R.id.remove_from_queue);
        }
    }

    /**
     * ItemTouchHelper callback for drag and swipe functionality.
     */
    public static class QueueItemTouchCallback extends ItemTouchHelper.Callback {
        private final QueueAdapter adapter;

        public QueueItemTouchCallback(QueueAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false; // We use drag handle instead
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return true;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
            int swipeFlags = ItemTouchHelper.START | ItemTouchHelper.END;
            return makeMovementFlags(dragFlags, swipeFlags);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            adapter.moveItem(viewHolder.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getBindingAdapterPosition();
            if (adapter.listener != null) {
                adapter.listener.onRemoveClick(position);
            }
        }
    }
}
