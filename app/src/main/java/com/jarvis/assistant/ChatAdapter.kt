package com.jarvis.assistant

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: MutableList<Message>) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view as LinearLayout
        val bubbleContainer: LinearLayout = view.findViewById(R.id.bubbleContainer)
        val senderLabel: TextView = view.findViewById(R.id.senderLabel)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val messageImage: ImageView = view.findViewById(R.id.messageImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.messageText.text = MarkdownUtils.toSpannable(message.text)

        if (message.imageBase64 != null) {
            try {
                val bytes = Base64.decode(message.imageBase64, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                holder.messageImage.setImageBitmap(bitmap)
                holder.messageImage.visibility = View.VISIBLE
            } catch (e: Exception) {
                holder.messageImage.visibility = View.GONE
            }
        } else {
            holder.messageImage.visibility = View.GONE
        }

        if (message.isUser) {
            holder.senderLabel.text = "VOUS"
            holder.bubbleContainer.setBackgroundResource(R.drawable.bg_bubble_user)
            holder.root.gravity = Gravity.END
        } else {
            holder.senderLabel.text = "JARVIS"
            holder.bubbleContainer.setBackgroundResource(R.drawable.bg_bubble_ai)
            holder.root.gravity = Gravity.START
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
}
