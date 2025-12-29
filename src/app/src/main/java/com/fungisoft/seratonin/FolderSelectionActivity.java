package com.fungisoft.seratonin;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;

/**
 * Activity for selecting the music folder and handling storage permissions.
 * This is shown when:
 * 1. First run (no folder selected)
 * 2. User wants to change the music folder
 * 3. Permission was revoked
 */
public class FolderSelectionActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "MusicFolderPrefs";
    public static final String KEY_MUSIC_FOLDER = "music_folder_path";
    public static final String KEY_FOLDER_SELECTED = "folder_selected";

    private TextView statusText;
    private TextView selectedFolderText;
    private MaterialButton grantPermissionBtn;
    private MaterialButton selectFolderBtn;
    private MaterialButton continueBtn;

    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private ActivityResultLauncher<Intent> manageStorageLauncher;
    
    // Track if folder was changed in this session
    private boolean folderChangedThisSession = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_folder_selection);
        
        // If user is explicitly changing folder, we'll want a full scan
        boolean isChangingFolder = getIntent().getBooleanExtra("changing_folder", false);
        if (isChangingFolder) {
            folderChangedThisSession = true;
        }

        // Apply window insets
        ConstraintLayout container = findViewById(R.id.folder_selection_container);
        ViewCompat.setOnApplyWindowInsetsListener(container, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        initViews();
        setupLaunchers();
        updateUI();
    }

    private void initViews() {
        statusText = findViewById(R.id.status_text);
        selectedFolderText = findViewById(R.id.selected_folder_text);
        grantPermissionBtn = findViewById(R.id.grant_permission_btn);
        selectFolderBtn = findViewById(R.id.select_folder_btn);
        continueBtn = findViewById(R.id.continue_btn);

        grantPermissionBtn.setOnClickListener(v -> requestStoragePermission());
        selectFolderBtn.setOnClickListener(v -> openFolderPicker());
        continueBtn.setOnClickListener(v -> proceedToMainActivity());
    }

    private void setupLaunchers() {
        // Launcher for folder picker
        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri treeUri = result.getData().getData();
                        if (treeUri != null) {
                            handleSelectedFolder(treeUri);
                        }
                    }
                    updateUI();
                }
        );

        // Launcher for MANAGE_EXTERNAL_STORAGE settings
        manageStorageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> updateUI()
        );
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - Request MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("All Files Access Required")
                        .setMessage("To load cover art from your music folders, Seratonin needs \"All Files Access\" permission.\n\n" +
                                "On the next screen, find Seratonin and enable the toggle.")
                        .setPositiveButton("Open Settings", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                manageStorageLauncher.launch(intent);
                            } catch (Exception e) {
                                // Fallback to general settings
                                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                                manageStorageLauncher.launch(intent);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        } else {
            // Android 10 and below - legacy permissions handled in MainActivity
            Toast.makeText(this, "Storage permission should be granted automatically", Toast.LENGTH_SHORT).show();
        }
    }

    private void openFolderPicker() {
        if (!hasStoragePermission()) {
            Toast.makeText(this, "Please grant storage permission first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Use a simple folder picker via Intent
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        
        // Try to start in Music folder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            if (musicDir.exists()) {
                intent.putExtra("android.provider.extra.INITIAL_URI", Uri.fromFile(musicDir));
            }
        }
        
        folderPickerLauncher.launch(intent);
    }

    private void handleSelectedFolder(Uri treeUri) {
        // Persist the URI permission
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // Permission might not be persistable, but we have MANAGE_EXTERNAL_STORAGE
        }

        // Convert SAF URI to file path
        String folderPath = convertTreeUriToPath(treeUri);
        
        if (folderPath != null) {
            // Save the folder path
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_MUSIC_FOLDER, folderPath)
                    .putBoolean(KEY_FOLDER_SELECTED, true)
                    .apply();
            
            // Mark that folder was changed this session
            folderChangedThisSession = true;
            
            Toast.makeText(this, "Music folder set: " + folderPath, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Could not determine folder path", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Convert a SAF tree URI to a filesystem path.
     * This works because we have MANAGE_EXTERNAL_STORAGE permission.
     */
    private String convertTreeUriToPath(Uri treeUri) {
        String docId = null;
        
        // Get the document ID from the tree URI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            } catch (Exception e) {
                return null;
            }
        }
        
        if (docId == null) {
            return null;
        }

        // Parse the document ID to get the path
        // Format is usually "primary:path/to/folder" or "storage-id:path/to/folder"
        String[] split = docId.split(":");
        String type = split[0];
        String relativePath = split.length > 1 ? split[1] : "";

        if ("primary".equalsIgnoreCase(type)) {
            return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relativePath;
        } else {
            // External SD card or other storage
            // Try to find the mount point
            File[] externalDirs = getExternalFilesDirs(null);
            for (File dir : externalDirs) {
                if (dir != null) {
                    String path = dir.getAbsolutePath();
                    // Extract the storage root (e.g., /storage/XXXX-XXXX)
                    int endIndex = path.indexOf("/Android/");
                    if (endIndex > 0) {
                        String storageRoot = path.substring(0, endIndex);
                        if (storageRoot.contains(type) || type.equals("home")) {
                            return storageRoot + "/" + relativePath;
                        }
                    }
                }
            }
            // Fallback: assume it's in external storage
            return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relativePath;
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            // For older versions, assume granted (checked in MainActivity)
            return true;
        }
    }

    private boolean hasFolderSelected() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String folderPath = prefs.getString(KEY_MUSIC_FOLDER, null);
        if (folderPath == null) {
            return false;
        }
        // Verify the folder still exists
        File folder = new File(folderPath);
        return folder.exists() && folder.isDirectory();
    }

    private String getSelectedFolder() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_MUSIC_FOLDER, null);
    }

    private void updateUI() {
        boolean hasPermission = hasStoragePermission();
        boolean hasFolder = hasFolderSelected();

        // Update permission status
        if (hasPermission) {
            grantPermissionBtn.setText("✓ Permission Granted");
            grantPermissionBtn.setEnabled(false);
            selectFolderBtn.setEnabled(true);
        } else {
            grantPermissionBtn.setText("Grant Storage Permission");
            grantPermissionBtn.setEnabled(true);
            selectFolderBtn.setEnabled(false);
        }

        // Update folder status
        if (hasFolder) {
            String folder = getSelectedFolder();
            selectedFolderText.setText("Selected: " + folder);
            selectedFolderText.setVisibility(View.VISIBLE);
        } else {
            selectedFolderText.setVisibility(View.GONE);
        }

        // Update continue button
        if (hasPermission && hasFolder) {
            continueBtn.setEnabled(true);
            statusText.setText("Ready! Tap Continue to start playing music.");
        } else if (hasPermission) {
            continueBtn.setEnabled(false);
            statusText.setText("Step 2: Select your music folder");
        } else {
            continueBtn.setEnabled(false);
            statusText.setText("Step 1: Grant storage permission to access cover art");
        }
    }

    private void proceedToMainActivity() {
        proceedToMainActivity(folderChangedThisSession);
    }
    
    private void proceedToMainActivity(boolean runFullScan) {
        boolean isChangingFolder = getIntent().getBooleanExtra("changing_folder", false);
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Signal MainActivity to do a full rescan with progress if folder was just selected/changed
        intent.putExtra("run_full_scan", runFullScan);
        intent.putExtra("is_folder_change", isChangingFolder);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
        
        // Check if this is a re-visit for changing folder
        boolean isChangingFolder = getIntent().getBooleanExtra("changing_folder", false);
        
        // If not changing folder and everything is set up, auto-proceed
        if (!isChangingFolder && hasStoragePermission() && hasFolderSelected()) {
            proceedToMainActivity(false); // No full scan needed for auto-proceed
        }
    }

    /**
     * Check if the app has proper setup (permission + folder selected).
     * Call this from MainActivity to decide whether to show this activity.
     */
    public static boolean isSetupComplete(android.content.Context context) {
        // Check permission
        boolean hasPermission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasPermission = Environment.isExternalStorageManager();
        } else {
            hasPermission = true; // Will be checked via runtime permission
        }
        
        // Check folder selection
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String folderPath = prefs.getString(KEY_MUSIC_FOLDER, null);
        boolean hasFolder = folderPath != null && new File(folderPath).exists();
        
        return hasPermission && hasFolder;
    }

    /**
     * Get the selected music folder path.
     */
    public static String getMusicFolderPath(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getString(KEY_MUSIC_FOLDER, null);
    }
}
