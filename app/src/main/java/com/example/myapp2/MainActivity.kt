package com.example.myapp2

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import kotlinx.coroutines.*
//import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
//import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest

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

                return@withContext response.body!!.string()
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

                return@withContext response.body!!.string()
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
                    responseBody = response.body!!.string()
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
                "ID: ${media.id}, Name: ${media.name}, Size: ${media.size}, Data: ${media.data}, Date: ${media.date}"
            )
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connected)

        val loginName = intent.getStringExtra("loginName")
        val statusTextView: TextView = findViewById(R.id.statusTextView)
        statusTextView.text = "Connected as $loginName"

        // Check for READ_EXTERNAL_STORAGE permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            != PackageManager.PERMISSION_GRANTED) {

            // Permission is not granted
            // Should we show an explanation?
            Log.d("Permission", "Requesting permission")
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            ) {
                Log.d("Permission", "Here 1")
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                Log.d("Permission", "Here 2")
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE)
                // MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        } else {
            Log.d("Permission", "Here 3")
            // Initialize the database
            val db = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java, "database-name"
            ).build()

            // Use lifecycleScope to launch a coroutine for database operation
            lifecycleScope.launch(Dispatchers.IO) {
                // Scan for photos and videos
                val mediaList = scanMediaAndFilterDuplicates(db)

                // Update the UI with the data on the main thread
                withContext(Dispatchers.Main) {
                    viewManager = LinearLayoutManager(this@ConnectedActivity)
                    viewAdapter = MyAdapter(mediaList)

                    recyclerView = findViewById<RecyclerView>(R.id.recyclerView).apply {
                        setHasFixedSize(true)
                        layoutManager = viewManager
                        adapter = viewAdapter
                    }
                }
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
            MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
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

    private suspend fun scanMediaAndFilterDuplicates(db: AppDatabase): List<Media> {
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

                    // Check if the file is already in the database
                    if (!db.mediaDao().isFileExists(data)) {
                        val media = Media(id, name, size, data, date)
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

        return mediaList
    }


}

class MyAdapter(private val myDataset: List<Media>) :
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



