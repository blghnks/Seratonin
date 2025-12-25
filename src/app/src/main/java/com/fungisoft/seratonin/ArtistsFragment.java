package com.fungisoft.seratonin;

import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static com.fungisoft.seratonin.MainActivity.musicFiles;

/**
 * Fragment to display a list of unique artists.
 */
public class ArtistsFragment extends Fragment {

    RecyclerView recyclerView;
    static ArtistAdapter artistAdapter;
    static ArrayList<String> artistsList = new ArrayList<>();

    public ArtistsFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_artists, container, false);
        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setHasFixedSize(true);
        
        // Extract unique artists from musicFiles
        artistsList.clear();
        if (musicFiles != null && !musicFiles.isEmpty()) {
            Set<String> uniqueArtists = new HashSet<>();
            for (MusicFiles music : musicFiles) {
                String artist = music.getArtist();
                if (artist != null && !artist.isEmpty() && !artist.equals("<unknown>")) {
                    uniqueArtists.add(artist);
                }
            }
            artistsList.addAll(uniqueArtists);
            // Sort alphabetically
            java.util.Collections.sort(artistsList, String.CASE_INSENSITIVE_ORDER);
            
            artistAdapter = new ArtistAdapter(getContext(), artistsList);
            recyclerView.setAdapter(artistAdapter);
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.VERTICAL, false));
        }
        return view;
    }
}
