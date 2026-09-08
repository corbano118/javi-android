package com.javi.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class JaviTask(val id:String,val title:String,val done:Boolean=false)

object TaskStore {
    private const val PREFS="javi_tasks"
    private const val KEY="items"
    fun load(context:Context):List<JaviTask>{
        val raw=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"[]")?:"[]"
        val out=mutableListOf<JaviTask>()
        runCatching{val a=JSONArray(raw);for(i in 0 until a.length()){val o=a.getJSONObject(i);out+=JaviTask(o.optString("id"),o.optString("title"),o.optBoolean("done"))}}
        return out
    }
    fun save(context:Context,tasks:List<JaviTask>){val a=JSONArray();tasks.forEach{a.put(JSONObject().apply{put("id",it.id);put("title",it.title);put("done",it.done)})};context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,a.toString()).apply()}
    fun newTask(title:String)=JaviTask(UUID.randomUUID().toString(),title.trim(),false)
}
