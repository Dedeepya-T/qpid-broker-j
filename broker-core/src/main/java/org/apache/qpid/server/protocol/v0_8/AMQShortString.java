/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.qpid.server.protocol.v0_8;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.bytebuffer.QpidByteBuffer;

/**
 * A short string is a representation of an AMQ Short String
 * Short strings differ from the Java String class by being limited to on ASCII characters (0-127)
 * and thus can be held more effectively in a byte buffer.
 *
 */
public final class AMQShortString implements Comparable<AMQShortString>
{
    /**
     * The maximum number of octets in AMQ short string as defined in AMQP specification
     */
    public static final int MAX_LENGTH = 255;

    private static final Logger LOGGER = LoggerFactory.getLogger(AMQShortString.class);

    private final byte[] _data;
    private final int _offset;
    private int _hashCode;
    private String _asString = null;

    private final int _length;

    public static final AMQShortString EMPTY_STRING = createAMQShortString((String)null);

    private AMQShortString(byte[] data)
    {
        if (data == null)
        {
            throw new NullPointerException("Cannot create AMQShortString with null data[]");
        }
        if (data.length > MAX_LENGTH)
        {
            throw new IllegalArgumentException("Cannot create AMQShortString with number of octets over 255!");
        }
        _data = data.clone();
        _length = data.length;
        _offset = 0;
    }

    private AMQShortString(String string)
    {
        final byte[] data = EncodingUtils.asUTF8Bytes(string);
        final int length = data.length;
        if (data.length> MAX_LENGTH)
        {
            throw new IllegalArgumentException("Cannot create AMQShortString with number of octets over 255!");
        }

        int hash = 0;
        for (int i = 0; i < length; i++)
        {
            data[i] = (byte) (0xFF & data[i]);
            hash = (31 * hash) + data[i];
        }
        _hashCode = hash;
        _data = data;

        _length = length;
        _offset = 0;

        _asString = string == null ? "" : string;
    }

    private AMQShortString(byte[] data, final int offset, final int length)
    {
        if (length > MAX_LENGTH)
        {
            throw new IllegalArgumentException("Cannot create AMQShortString with number of octets over 255!");
        }
        if (data == null)
        {
            throw new NullPointerException("Cannot create AMQShortString with null data[]");
        }

        _offset = offset;
        _length = length;
        _data = data;
    }

    public static AMQShortString readAMQShortString(QpidByteBuffer buffer)
    {
        int length = buffer.getUnsignedByte();
        if(length == 0)
        {
            return null;
        }
        else
        {
            if (length > MAX_LENGTH)
            {
                throw new IllegalArgumentException("Cannot create AMQShortString with number of octets over 255!");
            }
            if(length > buffer.remaining())
            {
                throw new IllegalArgumentException("Cannot create AMQShortString with length "
                                                   + length + " from a ByteBuffer with only "
                                                   + buffer.remaining()
                                                   + " bytes.");

            }
            byte[] data = new byte[length];
            buffer.get(data);
            return new AMQShortString(data, 0, length);
        }
    }

    public static AMQShortString createAMQShortString(byte[] data)
    {
        return new AMQShortString(data);
    }

    public static AMQShortString createAMQShortString(String string)
    {
        return new AMQShortString(string);
    }

    /**
     * Get the length of the short string
     * @return length of the underlying byte array
     */
    public int length()
    {
        return _length;
    }

    public char charAt(int index)
    {

        return (char) _data[_offset + index];

    }

    public byte[] getBytes()
    {
        if(_offset == 0 && _length == _data.length)
        {
            return _data.clone();
        }
        else
        {
            byte[] data = new byte[_length];
            System.arraycopy(_data,_offset,data,0,_length);
            return data;
        }
    }

    public void writeToBuffer(QpidByteBuffer buffer)
    {
        final int size = length();
        buffer.put((byte)size);
        buffer.put(_data, _offset, size);
    }


    @Override
    public boolean equals(Object o)
    {

        if (o == this)
        {
            return true;
        }

        if(o instanceof AMQShortString)
        {
            return equals((AMQShortString) o);
        }

        return false;

    }

    public boolean equals(final AMQShortString otherString)
    {
        if (otherString == this)
        {
            return true;
        }

        if (otherString == null)
        {
            return false;
        }

        final int hashCode = _hashCode;

        final int otherHashCode = otherString._hashCode;

        if ((hashCode != 0) && (otherHashCode != 0) && (hashCode != otherHashCode))
        {
            return false;
        }

        final int length = _length;

        if(length != otherString._length)
        {
            return false;
        }


        final byte[] data = _data;

        final byte[] otherData = otherString._data;

        final int offset = _offset;

        final int otherOffset = otherString._offset;

        if(offset == 0 && otherOffset == 0 && length == data.length && length == otherData.length)
        {
            return Arrays.equals(data, otherData);
        }
        else
        {
            int thisIdx = offset;
            int otherIdx = otherOffset;
            for(int i = length;  i-- != 0; )
            {
                if(!(data[thisIdx++] == otherData[otherIdx++]))
                {
                    return false;
                }
            }
        }

        return true;

    }

    @Override
    public int hashCode()
    {
        int hash = _hashCode;
        if (hash == 0)
        {
            final int size = length();

            for (int i = 0; i < size; i++)
            {
                hash = (31 * hash) + _data[i+_offset];
            }

            _hashCode = hash;
        }

        return hash;
    }

    @Override
    public String toString()
    {
        if (_asString == null)
        {
            _asString = new String(_data, _offset, _length, StandardCharsets.UTF_8);
        }
        return _asString;
    }

    @Override
    public int compareTo(AMQShortString name)
    {
        if(name == this)
        {
            return 0;
        }
        else if (name == null)
        {
            return 1;
        }
        else
        {

            if (name.length() < length())
            {
                return -name.compareTo(this);
            }

            for (int i = 0; i < length(); i++)
            {
                final byte d = _data[i+_offset];
                final byte n = name._data[i+name._offset];
                if (d < n)
                {
                    return -1;
                }

                if (d > n)
                {
                    return 1;
                }
            }

            return (length() == name.length()) ? 0 : -1;
        }
    }

    public boolean contains(final byte b)
    {
        final int end = _length + _offset;
        for(int i = _offset; i < end; i++)
        {
            if(_data[i] == b)
            {
                return true;
            }
        }
        return false;
    }

    public static AMQShortString validValueOf(Object obj)
    {
        return valueOf(obj,true,true);
    }

    static AMQShortString valueOf(Object obj, boolean truncate, boolean nullAsEmptyString)
    {
        if (obj == null)
        {
            if (nullAsEmptyString)
            {
                return EMPTY_STRING;
            }
            return null;
        }
        else
        {
            String value = String.valueOf(obj);
            int strLength = Math.min(value.length(), AMQShortString.MAX_LENGTH);

            byte[] bytes = EncodingUtils.asUTF8Bytes(value);
            if(truncate)
            {
                while (bytes.length > AMQShortString.MAX_LENGTH)
                {
                    value = value.substring(0, strLength-- - 3) + "...";
                    bytes  = EncodingUtils.asUTF8Bytes(value);
                }

            }
            return createAMQShortString(bytes);
        }
    }

    public static AMQShortString valueOf(Object obj)
    {
        return valueOf(obj, false, false);
    }

    public static AMQShortString valueOf(String obj)
    {
        if(obj == null)
        {
            return null;
        }
        else
        {
            return createAMQShortString(obj);
        }

    }

    public static String toString(AMQShortString amqShortString)
    {
        return amqShortString == null ? null : amqShortString.toString();
    }

}
