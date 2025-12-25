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

/**
 * Activity to display albums filtered by a specific artist.
 */
public class ArtistAlbumsActivity extends AppCompatActivity {

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
        
        artistName = getIntent().getStringExtra("artistName");
        if (artistName != null) {
            artistNameText.setText(artistName);
        }
        
        // Collect unique albums for this artist
        Set<String> seenAlbums = new HashSet<>();
        if (musicFiles != null) {
            for (MusicFiles music : musicFiles) {
                if (artistName != null && artistName.equals(music.getArtist())) {
                    String album = music.getAlbum();
                    if (album != null && !seenAlbums.contains(album)) {
                        artistAlbums.add(music);
                        seenAlbums.add(album);
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
    protected void onResume() {
        super.onResume();
        if (!artistAlbums.isEmpty()) {
            albumAdapter = new AlbumAdapter(this, artistAlbums);
            recyclerView.setAdapter(albumAdapter);
            recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        }
    }
}
