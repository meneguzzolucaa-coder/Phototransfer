package com.example.lanfotoshare

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

data class SelectedItem(
    val uri: Uri,
    val name: String,
    val mime: String
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readParcelable(Uri::class.java.classLoader)!!,
        parcel.readString() ?: "",
        parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(uri, flags)
        parcel.writeString(name)
        parcel.writeString(mime)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SelectedItem> {
        override fun createFromParcel(parcel: Parcel): SelectedItem = SelectedItem(parcel)
        override fun newArray(size: Int): Array<SelectedItem?> = arrayOfNulls(size)
    }
}