package com.dropratesclog;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.StringJoiner;

/**
 * Reads a JSON value that may be a string, null, or an array of strings and returns a single
 * string (array elements joined with ", "). The wiki scraper occasionally emits an array for a
 * field that is usually scalar (e.g. a multi-line description), so deserialization stays tolerant
 * rather than aborting the whole file load on one irregular value.
 */
class StringOrArrayAdapter extends TypeAdapter<String>
{
    @Override
    public String read(JsonReader in) throws IOException
    {
        JsonToken token = in.peek();
        if (token == JsonToken.NULL)
        {
            in.nextNull();
            return null;
        }
        if (token == JsonToken.BEGIN_ARRAY)
        {
            StringJoiner joiner = new StringJoiner(", ");
            in.beginArray();
            while (in.hasNext())
            {
                if (in.peek() == JsonToken.NULL)
                {
                    in.nextNull();
                    continue;
                }
                joiner.add(in.nextString());
            }
            in.endArray();
            return joiner.length() == 0 ? null : joiner.toString();
        }
        return in.nextString();
    }

    @Override
    public void write(JsonWriter out, String value) throws IOException
    {
        out.value(value);
    }
}
