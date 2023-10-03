package com.example.myapp2

//import okhttp3.MediaType
//import okhttp3.RequestBody
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


import com.owncloud.android.lib.common.OwnCloudBasicCredentials
import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.OwnCloudClientFactory
import com.owncloud.android.lib.common.OwnCloudCredentialsFactory
import com.owncloud.android.lib.common.network.OnDatatransferProgressListener
import com.owncloud.android.lib.common.operations.OnRemoteOperationListener
import com.owncloud.android.lib.common.operations.RemoteOperation
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.resources.files.DownloadFileRemoteOperation
import com.owncloud.android.lib.resources.files.FileUtils
import com.owncloud.android.lib.resources.files.ReadFolderRemoteOperation
import com.owncloud.android.lib.resources.files.RemoveFileRemoteOperation
import com.owncloud.android.lib.resources.files.UploadFileRemoteOperation
import com.owncloud.android.lib.resources.files.model.RemoteFile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayList


class MainActivity : AppCompatActivity() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var isRequestInProgress = false // Flag to track if a request is in progress

    private suspend fun postRequest(url: String): String {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url + "/index.php/login/v2")
                .post("".toRequestBody()) // Use an empty POST body
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                return@withContext response.body.string()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if loginName and appPassword exist
        val sharedPref = getSharedPreferences("MyApp", Context.MODE_PRIVATE)
        val savedLoginName = sharedPref.getString("loginName", null)
        val savedAppPassword = sharedPref.getString("appPassword", null)

        if (savedLoginName != null && savedAppPassword != null) {
            // If loginName and appPassword exist, start the next activity
            val intent = Intent(this, ConnectedActivity::class.java).apply {
                putExtra("loginName", savedLoginName)
                putExtra("appPassword", savedAppPassword)
            }
            startActivity(intent)
            finish()  // Close MainActivity
        } else {
            // If loginName and appPassword do not exist, show the main page
            setContentView(R.layout.activity_main)

            val connectButton: Button = findViewById(R.id.connectButton)
            connectButton.setOnClickListener {
                if (!isRequestInProgress) { // Check if a request is not already in progress
                    isRequestInProgress =
                        true // Set the flag to indicate that a request is in progress

                    val url = findViewById<EditText>(R.id.urlField).text.toString()
                    // Save deletionTime in SharedPreferences
                    val deletionTime = findViewById<EditText>(R.id.deletionTime).text.toString()
                    Log.d("Shared Pref", "URL: $url")
                    with (sharedPref.edit()) {
                        putString("deletionTime", deletionTime)
                        putString("nextCloudURL", url)
                        apply()
                    }


                    val statusField: TextView = findViewById(R.id.statusField)
                    scope.launch {
                        try {
                            val result = postRequest(url)
                            val token = handleResult(result, url)
                            withTimeoutOrNull(20000) { // Timeout after 20 seconds
                                val pollResult = pollEndpoint(url, token)
                                handlePollResult(pollResult)
                            } ?: run {
                                statusField.text = "Failed to connect"
                            }
                        } finally {
                            isRequestInProgress = false // Reset the flag when the request is done
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private suspend fun postRequest(url: String, content: ByteArray): String {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url + "/index.php/login/v2")
                .post(content.toRequestBody(null, 0, content.size)) // Use content as POST body
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                return@withContext response.body.string()
            }
        }
    }

    private fun handleResult(result: String, url: String): String {
        var token = ""
        try {
            val json = JSONObject(result)
            if (json.has("poll")) {
                val poll = json.getJSONObject("poll")
                if (poll.has("token")) {
                    token = poll.getString("token")
                }
            }
            if (json.has("login")) {
                var loginUrl = json.getString("login")

                // Remove escape characters from the URL
                loginUrl = loginUrl.replace("\\", "")
                println("URL: $loginUrl")
                // Open the browser with the login URL
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(loginUrl)
                )
                startActivity(browserIntent)
            } else {
                println("'login' field not found in JSON response")
                println("URL: $url")
                println("JSON: $result")
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return token
    }

    suspend fun pollEndpoint(url: String, token: String): String {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val requestBody = "token=$token"
                .toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url + "/login/v2/poll")
                .post(requestBody)
                .build()

            var response: Response
            var responseBody = ""
            do {
                response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    responseBody = response.body.string()
                    Log.d("PollingData", "Data: $responseBody")
                } else {
                    Log.e("PollingError", "Error: ${response.message}")
                }
                delay(1000) // Wait for 1 second before polling again
            } while (!response.isSuccessful)

            return@withContext responseBody
        }
    }

    fun handlePollResult(result: String) {
        try {
            val json = JSONObject(result)
            val loginName = json.getString("loginName")
            val appPassword = json.getString("appPassword")

            // Save loginName and appPassword in SharedPreferences
            val sharedPref = getSharedPreferences("MyApp", Context.MODE_PRIVATE)
            with (sharedPref.edit()) {
                putString("loginName", loginName)
                putString("appPassword", appPassword)

                apply()
            }

            // Use loginName and appPassword here
            Log.d("PollingData", "LoginName: $loginName, AppPassword: $appPassword")

            // Start a new Activity
            val intent = Intent(this, ConnectedActivity::class.java).apply {
                putExtra("loginName", loginName)
            }
            startActivity(intent)
        } catch (e: JSONException) {
            e.printStackTrace()
            Log.e("PollingError", "Error: ${e.message}")
        }
    }

}

class ConnectedActivity : AppCompatActivity() {
    lateinit var recyclerView: RecyclerView
    lateinit var viewAdapter: RecyclerView.Adapter<*>
    lateinit var viewManager: RecyclerView.LayoutManager

    companion object {
        const val MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 16234
    }

    private fun logDatabaseEntries(mediaList: List<Media>) {
        for (media in mediaList) {
            Log.d(
                "DatabaseEntry",
                "ID: ${media.id}, Name: ${media.name}, Size: ${media.size}, Data: ${media.data}, Date: ${media.date}, Status: ${media.status}"
            )
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connected)

        // Initialize the recyclerView
        recyclerView = findViewById(R.id.recyclerView)



        val userName = intent.getStringExtra("loginName")
        val statusText = findViewById<TextView>(R.id.statusTextView)
        statusText.text = "Connected as $userName"

        val sharedPref = getSharedPreferences("MyApp", Context.MODE_PRIVATE)
        val deletionTime = sharedPref.getString("deletionTime", null)
        val nextCloudURL = sharedPref.getString("nextCloudURL", null)
        val appPassword = sharedPref.getString("appPassword", null)


        Log.d("Connect", "URL: $nextCloudURL")
        Log.d("Connect", "User: $userName")
        Log.d("Connect", "Pass: $appPassword")
        Log.d("Connect", "Deletion Days: $deletionTime")

// Create untrusted client instance
        val client = OwnCloudClientFactory.createOwnCloudClient(
            Uri.parse(nextCloudURL),
            this,
            false
        )
// Set credentials
        client.credentials = OwnCloudCredentialsFactory.newBasicCredentials(
            userName,
            appPassword
        )

        // Check if the client was created successfully
        if (client != null) {
            Log.d("Login", "Client Success")
        } else {
            Log.d("Login", "Client Failed")
        }

        // Use Kotlin coroutine to perform network operations
        lifecycleScope.launch {
            try {

                // Perform a simple operation to verify if the user is logged in

                // Before withContext
                Log.d("Coroutine", "Before withContext")
                // Use withContext(Dispatchers.IO) to move the network operation to a background thread

                val result = withContext(Dispatchers.IO) {
                    ReadFolderRemoteOperation("/").execute(client)
                }
                // Before withContext
                Log.d("Coroutine", "After withContext")

                if (result.isSuccess) {
                    Log.d("Login", "User is logged in")
                } else {
                    Log.d("Login", "User is not logged in")
                    Log.e("Login", "Error Message: ${result.message}")
                    Log.e("Login", "Error Details: ${result.getLogMessage()}")
                }

                //ReadFolderRemoteOperation("./")
                //RemoteOperationResult(client)
                val rootFolderPath = "/$userName" // Replace with the actual root folder path
                val task = RecursiveFileSearchTask(client, "IMG_20230113_201408.jpg")
                task.execute(rootFolderPath)


                if (result != null) {
                    // File found, handle it here
                } else {
                    // File not found
                    Log.d("File:", "File not found")
                }
            } catch (e: Exception) {
                // Handle exceptions here
                Log.e("NetworkError", "An error occurred: ${e.message}", e)
            }
        }

        if (deletionTime != null) {
            // Use the deletionTime value as needed
            val statusDeletionTime = findViewById<TextView>(R.id.statusTimeView)
            statusDeletionTime.text = "Delete photos/videos after $deletionTime days."
        } else {
            Log.d("Deletion Time", "Error Retrieving")
        }



        // Check for READ_EXTERNAL_STORAGE permission

        if (checkSelfPermission(READ_EXTERNAL_STORAGE) != PERMISSION_GRANTED) {

            // Permission is not granted
            // Should we show an explanation?
            Log.d("Permission", "Requesting permission")
            if (shouldShowRequestPermissionRationale(READ_EXTERNAL_STORAGE)) {
                Log.d("Permission", "Here 1")
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                Log.d("Permission", "Here 2")
                // No explanation needed, we can request the permission.
                requestPermissions(arrayOf(READ_EXTERNAL_STORAGE), 43534)

            }
        } else {
            Log.d("Permission", "Here 3")
            // Initialize the database
            val db = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java,
                "database"
            ).build()



            // Use lifecycleScope to launch a coroutine for database operation
            lifecycleScope.launch(Dispatchers.IO) {
                // Scan for photos and videos
                val media = getMediaAndFilterDuplicates(db)

                // Before withContext
                Log.d("Coroutine", "Before withContext2")
                // Update the UI with the data on the main thread
                withContext(Dispatchers.Main) {

                    val manager = LinearLayoutManager(this@ConnectedActivity)
                    val adapter = MyAdapter(media)

                    findViewById<RecyclerView>(R.id.recyclerView).apply {
                        setHasFixedSize(true)
                        layoutManager = manager
                        this.adapter = adapter
                    }
                }
                // After withContext
                Log.d("Coroutine", "After withContext2")
            }
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        Log.d("Permission", "Results: ${grantResults.toList()}")
       super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            43534 -> {
                if (grantResults[0] == PERMISSION_GRANTED) {
                    // Permission was granted
                    // You can proceed with accessing the media files here
                    Log.d("Permission", "Permissions Granted")
                } else {
                    // Permission was denied
                    // Disable the functionality that depends on this permission
                    Log.d("Permission", "Permissions Denied")
                }
                return
            }
            else -> {
                // Ignore all other requests
            }
        }
    }

    private suspend fun getMediaAndFilterDuplicates(db: AppDatabase): MutableList<Media> {
        // Find the ProgressBar and TextViews by their IDs
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val scanningStatusTextView = findViewById<TextView>(R.id.scanningStatusTextView)
        val scannedStatusTextView = findViewById<TextView>(R.id.scannedStatusTextView)
        withContext(Dispatchers.Main) {
// Show the "Scanning..." message and ProgressBar
 Log.d("ConnectedActivity", "Scanning....")
        scanningStatusTextView.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE

// Hide other elements you don't want to display during scanning
// For example, you can hide the RecyclerView or any other UI elements
        recyclerView.visibility = View.GONE
    }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DATE_ADDED

        )

        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"

        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        val cursor = contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null
        )

        val mediaList = mutableListOf<Media>()

        cursor?.use {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)

            try {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val size = cursor.getLong(sizeColumn)
                    val data = cursor.getString(dataColumn)
                    val date = cursor.getLong(dateColumn)

                    // Set the status to "Exists" initially
                    val status = "Exists"

                    // Check if the file is already in the database
                    if (!db.mediaDao().isFileExists(data)) {
                        val media = Media(id, name, size, data, date, status)
                        db.mediaDao().insertAll(media)
                        mediaList.add(media)
                    }
                }
            } catch (e: Exception) {
                // Log any exceptions that may occur during scanning
                Log.e("ConnectedActivity", "Error while scanning media: ${e.message}")
            }
        }

        // Log the size of the mediaList
        Log.d("ConnectedActivity", "Media List Size: ${mediaList.size}")
        // Get the total count of scanned files
        val newlyScannedFilesCount = mediaList.size

       // Get the total count of all files in the database
        val totalFilesCount = db.mediaDao().getTotalFilesCount()

        runOnUiThread {
            // Find the TextView by its ID
            val totalFilesTextView = findViewById<TextView>(R.id.totalFilesTextView)
            // Update the TextView with the total files count
            totalFilesTextView.text = "Total Files: $totalFilesCount"
        }


        withContext(Dispatchers.Main) {
        // After scanning is complete, hide the "Scanning..." message and ProgressBar
        scanningStatusTextView.visibility = View.GONE
        progressBar.visibility = View.GONE

        // Show the "Scanned into database: (list here)" message with the total count
        scannedStatusTextView.visibility = View.VISIBLE
            scannedStatusTextView.text = "Scanned into database: $newlyScannedFilesCount"
        }
        return mediaList

    }


}

class MyAdapter(private val myDataset: MutableList<Media>) :
    RecyclerView.Adapter<MyAdapter.MyViewHolder>() {

    class MyViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): MyAdapter.MyViewHolder {
        val textView = TextView(parent.context)
        return MyViewHolder(textView)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.textView.text =
            "Filename: ${myDataset[position].name}, Size: ${myDataset[position].size}, Location: ${myDataset[position].data}"
    }

    override fun getItemCount() = myDataset.size
}

class RecursiveFileSearchTask(private val client: OwnCloudClient, private val fileName: String) :
    AsyncTask<String, Void, RemoteFile?>() {

    override fun doInBackground(vararg params: String): RemoteFile? {
        return searchFileRecursive(client, params[0], fileName)
    }

    private fun searchFileRecursive(
        client: OwnCloudClient,
        folderPath: String,
        fileName: String
    ): RemoteFile? {
        Log.d("RecursiveSearch", "Searching in folder: $folderPath")
        val readFolderOperation = ReadFolderRemoteOperation(folderPath)
        val result = readFolderOperation.execute(client)

        if (result.isSuccess) {
            val files: ArrayList<RemoteFile> = result.data as ArrayList<RemoteFile>

            for (file in files) {
                Log.d("RecursiveSearch", "Checking file: ${file.remotePath}")

                if (file.remotePath?.endsWith("/$fileName") == true) {
                    Log.d("RecursiveSearch", "Found file: ${file.remotePath}")
                    return file // Return the file if its path ends with the specified file name
                } else if (file.mimeType == "DIR") {
                    // If the item is a folder, recursively search inside it
                    val subFolderPath = folderPath + "/" + file.remotePath
                    val subFolderResult = searchFileRecursive(client, subFolderPath, fileName)
                    if (subFolderResult != null) {
                        Log.d("RecursiveSearch", "Found file in subfolder: ${subFolderResult.remotePath}")
                        return subFolderResult // Return the file if found in a subfolder
                    }
                }
            }
        } else {
            Log.e("RecursiveSearch", "Error reading folder: $result.response?.responseMessage")
        }
        return null
    }

    override fun onPostExecute(file: RemoteFile?) {
        if (file != null) {
            // File found, you can handle it here
            // Log.d("File:", file.remotePath)
        } else {
            // File not found
            Log.d("File:", "File not found")
        }
    }
}




