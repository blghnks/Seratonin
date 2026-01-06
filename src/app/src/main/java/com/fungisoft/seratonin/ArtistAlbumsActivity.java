package com.fungisoft.seratonin;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static com.fungisoft.seratonin.MainActivity.musicFiles;

public class ArtistAlbumsActivity extends AppCompatActivity {
    private static final String STATE_ARTIST_NAME = "artist";

    RecyclerView recyclerView;
    ImageView backButton;
    TextView artistNameText;
    String artistName;
    ArrayList<MusicFiles> artistAlbums = new ArrayList<>();
    AlbumAdapter albumAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_artist_albums);
        
        // Apply window insets for proper padding
        ConstraintLayout container = findViewById(R.id.artist_albums_container);
        ViewCompat.setOnApplyWindowInsetsListener(container, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        
        recyclerView = findViewById(R.id.recyclerView);
        backButton = findViewById(R.id.back_button);
        artistNameText = findViewById(R.id.artist_name_title);
        
        // Restore state from savedInstanceState first, then fall back to intent
        if (savedInstanceState != null) {
            artistName = savedInstanceState.getString(STATE_ARTIST_NAME);
        } else {
            artistName = getIntent().getStringExtra("artistName");
        }
        if (artistName != null) {
            artistNameText.setText(artistName);
        }
        
        // Collect unique albums for this artist
        Set<String> seenAlbums = new HashSet<>();
        if (musicFiles != null) {
            for (MusicFiles music : musicFiles) {
                if (artistName != null && artistName.equals(music.getArtist())) {
                    String album = music.getAlbum();
                    // Use normalized album name for deduplication to handle encoding issues
                    String normalizedAlbum = StringNormalizer.normalizeForComparison(album);
                    if (album != null && !seenAlbums.contains(normalizedAlbum)) {
                        artistAlbums.add(music);
                        seenAlbums.add(normalizedAlbum);
                    }
                }
            }
        }
        
        // Back button click
        backButton.setOnClickListener(v -> finish());
        
        // Register back press callback
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });
    }
    
    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ARTIST_NAME, artistName);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!artistAlbums.isEmpty()) {
            albumAdapter = new AlbumAdapter(this, artistAlbums);
            recyclerView.setAdapter(albumAdapter);
            int spanCount = getResources().getConfiguration().orientation == 
                android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 3 : 2;
            recyclerView.setLayoutManager(new GridLayoutManager(this, spanCount));
        }
    }
}
