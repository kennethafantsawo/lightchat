export interface User {
  id: string;
  username: string;
  password_hash: string;
  first_name: string;
  last_name: string;
  age: number;
  gender: string;
  email: string | null;
  phone: string | null;
  avatar_url: string | null;
  color: string;
  created_at: number;
}

export type PublicUser = Omit<User, "password_hash" | "email" | "phone" | "age" | "gender"> & {
  email?: string;
  phone?: string;
  age?: number;
  gender?: string;
  is_self?: boolean;
};

export type MessageType = "text" | "emoji" | "sticker" | "photo" | "video" | "audio" | "system";

export interface Message {
  id: string;
  conv_id: string;
  sender_id: string;
  type: MessageType;
  body: string | null;
  media_key: string | null;
  mime: string | null;
  duration_ms: number | null;
  status: "sent" | "delivered" | "read";
  created_at: number;
}

export interface MediaInfo {
  key: string;
  size: number;
  mime: string;
  created_at: number;
  expires_at: number;
}
