package com.fungisoft.seratonin;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;

import static com.fungisoft.seratonin.MainActivity.musicFiles;

public class AlbumDetails extends AppCompatActivity {

    RecyclerView recyclerView;
    ImageView albumPhoto, backButton;
    TextView passAlbumName, passArtistName;
    String albumName, artistName;
    ArrayList<MusicFiles> albumSongs = new ArrayList<>();
    AlbumDetailsAdapter albumDetailsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_album_details);
        
        // Apply window insets for proper padding
        ConstraintLayout container = findViewById(R.id.album_details_container);
        ViewCompat.setOnApplyWindowInsetsListener(container, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        
        recyclerView = findViewById(R.id.recyclerView);
        albumPhoto = findViewById(R.id.albumPhoto);
        passArtistName = findViewById(R.id.art_name);
        passAlbumName = findViewById(R.id.alb_name);
        backButton = findViewById(R.id.back_button);
        
        // Back button click
        backButton.setOnClickListener(v -> finish());
        
        artistName = getIntent().getStringExtra("artistName");
        albumName = getIntent().getStringExtra("albumName");
        passAlbumName.setText(albumName);
        passArtistName.setText(artistName);
        int j = 0;
        for (int i = 0 ; i < musicFiles.size() ; i ++) {
            if (albumName.equals(musicFiles.get(i).getAlbum())){
                albumSongs.add(j, musicFiles.get(i));
                j++;
            }
        }
        // Use album art loading: external first, then embedded fallback (album-level display)
        byte[] image = AlbumArtHelper.getAlbumArtForAlbum(this, albumSongs.get(0).getPath());
        if (image != null){
            Glide.with(this)
                    .load(image)
                    .into(albumPhoto);
        }
        else {
            Glide.with(this)
                    .load(R.drawable.musicicon)
                    .into(albumPhoto);
        }

        // Register back press callback to replace deprecated onBackPressed()
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                int count = getSupportFragmentManager().getBackStackEntryCount();
                if (count == 0) {
                    finish();
                } else {
                    getSupportFragmentManager().popBackStack();
                    getSupportFragmentManager().popBackStackImmediate();
                }
            }
        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!(albumSongs.isEmpty())){
            albumDetailsAdapter = new AlbumDetailsAdapter(this, albumSongs);
            recyclerView.setAdapter(albumDetailsAdapter);
            recyclerView.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL,false));
        }
    }
}