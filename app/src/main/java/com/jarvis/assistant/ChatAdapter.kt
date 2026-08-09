package com.jarvis.assistant

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.messageText.text = message.text

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
