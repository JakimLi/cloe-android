package com.cloe.android

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class ActionPreviewAdapter : RecyclerView.Adapter<ActionPreviewAdapter.ViewHolder>() {

    private val actions = mutableListOf<Pair<String, String>>()

    fun submitData(data: Map<String, String>) {
        actions.clear()
        for ((k, v) in data) {
            actions.add(k to v)
        }
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivGif: ImageView = view.findViewById(R.id.iv_action_gif)
        val tvName: TextView = view.findViewById(R.id.tv_action_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_action_preview, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (name, path) = actions[position]
        holder.tvName.text = name
        Glide.with(holder.itemView.context)
            .asGif()
            .load(path)
            .into(holder.ivGif)
    }

    override fun getItemCount(): Int = actions.size
}
