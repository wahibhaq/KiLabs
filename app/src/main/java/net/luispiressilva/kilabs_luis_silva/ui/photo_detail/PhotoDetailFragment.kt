package net.luispiressilva.kilabs_luis_silva.ui.photo_detail

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import kotlinx.android.synthetic.main.photo_detail_fragment.*
import kotlinx.android.synthetic.main.photo_detail_fragment.view.*
import net.luispiressilva.kilabs_luis_silva.components.viewmodel.ViewModelFactory
import net.luispiressilva.kilabs_luis_silva.di.component.DaggerViewModelComponent
import net.luispiressilva.kilabs_luis_silva.di.modules.network.NetworkModule
import net.luispiressilva.kilabs_luis_silva.di.modules.network.OkHttpClientModule
import net.luispiressilva.kilabs_luis_silva.model.PhotoFlickr
import javax.inject.Inject
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import android.graphics.Bitmap
import android.os.Environment.DIRECTORY_PICTURES
import android.os.Environment.getExternalStoragePublicDirectory
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import io.reactivex.Single
import net.luispiressilva.kilabs_luis_silva.*
import net.luispiressilva.kilabs_luis_silva.components.AppSchedulers
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException


class PhotoDetailFragment : Fragment(),
    Contracts.IFlickrPhotoDetailView {

    companion object {
        const val TAG = "PHOTODETAIL_FRAGMENT"
        const val PHOTO_KEY = "PHOTO_KEY"


        fun newInstance() = PhotoDetailFragment()
        fun newInstance(photo: PhotoFlickr): PhotoDetailFragment {
            val frag = newInstance()
            val bundle = Bundle()
            bundle.putParcelable(PHOTO_KEY, photo)
            frag.arguments = bundle
            return frag
        }
    }


    @Inject
    lateinit var viewModelFactory: ViewModelFactory
    lateinit var presenter: PhotoDetailViewModel

    private lateinit var photo: PhotoFlickr

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bundle = this.arguments
        if (bundle != null && bundle.containsKey(PHOTO_KEY)) {
            photo = bundle.getParcelable(PHOTO_KEY)
        }

        //prepare dependencies
        //presenter comunicates with bottom layers
        DaggerViewModelComponent.builder()
            .applicationContext(KiLabsApp.app)
            .useCache(OkHttpClientModule.UseCache(false))
            .host(NetworkModule.Host(FLICKR_URL))
            .build()
            .inject(this)


        presenter = ViewModelProviders.of(this, viewModelFactory).get(PhotoDetailViewModel::class.java)
        presenter.attachView(this, lifecycle)


    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.photo_detail_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        photo_detail_fragment_toolbar_back.setOnClickListener {
            activity?.onBackPressed()
        }

        photo_detail_fragment_toolbar_share.setOnClickListener {
            Single.fromCallable { saveImageToCache(this@PhotoDetailFragment.context, photo)
            }.subscribeOn(AppSchedulers().io).observeOn(AppSchedulers().android)
                .subscribe { fileName ->
                    share(fileName)
                }
        }

        val url = if (photo.urlC.isBlank()) photo.urlO else photo.urlC

        //we load medium size (fast) then when original is ready we show it with a fading effect
        Glide.with(view.context).load(photo.urlO)
            .transition(DrawableTransitionOptions.withCrossFade())
            .thumbnail(Glide.with(this).load(photo.urlC).apply(RequestOptions.centerCropTransform()))
            .into(view.photo_detail_image)



        photo_detail_button_go.setOnClickListener {
            openInBrowser(url)
        }
        photo_detail_button_save_image.setOnClickListener {
            Permissions.check(this@PhotoDetailFragment.context, Manifest.permission.WRITE_EXTERNAL_STORAGE, null, object : PermissionHandler() {
                //we do not dispose should complete by itself (we use application context to show result to user)
                @SuppressLint("CheckResult")
                override fun onGranted() {
                    Single.fromCallable { saveImageToPublic(photo)
                    }.subscribeOn(AppSchedulers().io).observeOn(AppSchedulers().android)
                        .subscribe { fileName ->
                            Toast.makeText(KiLabsApp.app, "$fileName saved", Toast.LENGTH_LONG).show()
                        }
                }
            })
        }
        photo_detail_button_show_metadata_image.setOnClickListener {
            showHideMetadata()
        }


        //allows textview to scroll the content
        photo_detail_image_metadada.movementMethod = ScrollingMovementMethod()

        presenter.start(photo.id)
    }


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)


    }


    fun showHideMetadata() {
        if (photo_detail_image_metadada_container.visibility == GONE) {
            photo_detail_button_show_metadata_image.text = getString(R.string.photo_detail_button_show_image_text)
            photo_detail_image.visibility = GONE
            photo_detail_image_metadada_container.visibility = VISIBLE
        } else {
            photo_detail_button_show_metadata_image.text = getString(R.string.photo_detail_button_show_metadata_text)
            photo_detail_image.visibility = VISIBLE
            photo_detail_image_metadada_container.visibility = GONE
        }
    }


    fun openInBrowser(url: String) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            activity?.startActivity(browserIntent)
        } catch (e: ActivityNotFoundException) {

        }
    }

    override fun setPhotoMetaData(metadata: String) {
        if (metadata.isBlank()) {
            photo_detail_image_metadada_retry.visibility = VISIBLE
        } else {
            photo_detail_image_metadada_retry.visibility = GONE
        }
        photo_detail_image_metadada.text = metadata
    }

    override fun showNoContentError(error: String) {

    }






    //following functions need refractoring (be placed properly as utils and others helper functions)

    private fun share(image : File?){
        if (image != null && activity?.applicationContext != null) {
            val uri = FileProvider.getUriForFile(activity?.applicationContext!!, BuildConfig.APPLICATION_ID + ".fileProvider", image)

            val sharingIntent = ShareCompat.IntentBuilder.from(activity)
                .setType("image/jpg")
                .setChooserTitle("KI Labs sharing")
                .setText("shared Flickr photo")
                .setStream(uri)
                .createChooserIntent()
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity?.startActivity(sharingIntent)
        }
    }

    private fun saveImageToCache(ctx : Context?, photo : PhotoFlickr): File? {
        if(ctx != null) {
            val url = if (photo.urlC.isBlank()) photo.urlO else photo.urlC

            val imageBitmap = Glide.with(ctx).asBitmap().load(url).submit().get()
            val imageFileName = "JPEG_" + photo.title + ".jpg"

            val imageFile = File(cacheDir(), imageFileName)

            try {
                val fOut = FileOutputStream(imageFile)
                imageBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fOut)
                fOut.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return imageFile
        }
        return null

    }

    //we check if we already have the image in the cache folder
    //yes -> copy to public system images folder and broadcast
    //no -> download to system images and broadcast
    private fun saveImageToPublic(photo : PhotoFlickr): String? {
        val url = if (photo.urlC.isBlank()) photo.urlO else photo.urlC

        val imageFileName = "JPEG_" + photo.title + ".jpg"
        val image = File(cacheDir(), imageFileName)


        var savedImagePath: String? = null
        val storageDir = getExternalStoragePublicDirectory(DIRECTORY_PICTURES)


        if(image.exists()){
            try {
                copy(image, File(storageDir, imageFileName))
            } catch (e : IOException){
                return "error"
            }
            return imageFileName
        } else {
            val imageBitmap = Glide.with(this@PhotoDetailFragment).asBitmap().load(url).submit().get()

            var success = true
            if (!storageDir.exists()) {
                success = storageDir.mkdirs()
            }
            if (success) {
                val imageFile = File(storageDir, imageFileName)
                savedImagePath = imageFile.getAbsolutePath()
                try {
                    val fOut = FileOutputStream(imageFile)
                    imageBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fOut)
                    fOut.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Add the image to the system gallery
                galleryAddPic(savedImagePath)

                return imageFileName
            }
        }

        return "error"

    }

    private fun cacheDir(): File {
        val cacheDir = File(KiLabsApp.app.cacheDir.path + File.separator + CACHE_FOLDER)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }

    //allows other apps to know a new image was added
    private fun galleryAddPic(imagePath: String) {
        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        val f = File(imagePath);
        val contentUri = Uri.fromFile(f);
        mediaScanIntent.setData(contentUri);
        context?.sendBroadcast(mediaScanIntent);
    }

    @Throws(IOException::class)
    fun copy(src: File, dst: File) {
        val `in` = FileInputStream(src)
        try {
            val out = FileOutputStream(dst)
            try {
                // Transfer bytes from in to out
                val buf = ByteArray(1024)
                var len: Int
                do {
                    len = `in`.read(buf)
                    out.write(buf, 0, len)
                } while (len > 0)
            } finally {
                out.close()
            }
        } finally {
            `in`.close()
        }
    }
}