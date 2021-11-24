package dev.ragnarok.fenrir.db.model.entity;

import java.util.List;

public class KeyboardEntity {
    private boolean one_time;
    private boolean inline;
    private int author_id;
    private List<List<ButtonEntity>> buttons;

    public KeyboardEntity() {
    }

    public boolean getOne_time() {
        return one_time;
    }

    public KeyboardEntity setOne_time(boolean one_time) {
        this.one_time = one_time;
        return this;
    }

    public boolean getInline() {
        return inline;
    }

    public KeyboardEntity setInline(boolean inline) {
        this.inline = inline;
        return this;
    }

    public int getAuthor_id() {
        return author_id;
    }

    public KeyboardEntity setAuthor_id(int author_id) {
        this.author_id = author_id;
        return this;
    }

    public List<List<ButtonEntity>> getButtons() {
        return buttons;
    }

    public KeyboardEntity setButtons(List<List<ButtonEntity>> buttons) {
        this.buttons = buttons;
        return this;
    }

    public static class ButtonEntity {
        private String color;
        private String type;
        private String label;
        private String link;
        private String payload;

        public ButtonEntity() {
        }

        public String getColor() {
            return color;
        }

        public ButtonEntity setColor(String color) {
            this.color = color;
            return this;
        }

        public String getType() {
            return type;
        }

        public ButtonEntity setType(String type) {
            this.type = type;
            return this;
        }

        public String getLabel() {
            return label;
        }

        public ButtonEntity setLabel(String label) {
            this.label = label;
            return this;
        }

        public String getLink() {
            return link;
        }

        public ButtonEntity setLink(String link) {
            this.link = link;
            return this;
        }

        public String getPayload() {
            return payload;
        }

        public ButtonEntity setPayload(String payload) {
            this.payload = payload;
            return this;
        }
    }
}
