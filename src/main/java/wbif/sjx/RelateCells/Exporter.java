package wbif.sjx.RelateCells;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.text.DecimalFormat;

class Exporter {
    static Element summariseAll(Document doc, Result res) {
        Element collection = doc.createElement("EXPERIMENT");

        if (res.getFile() != null) {
            Attr filepath = doc.createAttribute("FILEPATH");
            filepath.appendChild(doc.createTextNode(res.getFile().getAbsolutePath()));
            collection.setAttributeNode(filepath);
        }

        if (res.getWell() != null) {
            Attr well = doc.createAttribute("WELL");
            well.appendChild(doc.createTextNode(String.valueOf(res.getWell())));
            collection.setAttributeNode(well);
        }

        if (res.getField() != -1) {
            Attr field = doc.createAttribute("FIELD");
            field.appendChild(doc.createTextNode(String.valueOf(res.getField())));
            collection.setAttributeNode(field);
        }

        if (res.getTimepoint() != -1) {
            Attr timepoint = doc.createAttribute("TIMEPOINT");
            timepoint.appendChild(doc.createTextNode(String.valueOf(res.getTimepoint())));
            collection.setAttributeNode(timepoint);
        }

        if (res.getZ() != -1) {
            Attr z_pos = doc.createAttribute("Z_POS");
            z_pos.appendChild(doc.createTextNode(String.valueOf(res.getZ())));
            collection.setAttributeNode(z_pos);
        }

        if (res.getChannel() != -1) {
            Attr channel = doc.createAttribute("CHANNEL");
            channel.appendChild(doc.createTextNode(String.valueOf(res.getChannel())));
            collection.setAttributeNode(channel);
        }

        if (res.getCelltype() != null) {
            Attr cell_type = doc.createAttribute("CELL_TYPE");
            cell_type.appendChild(doc.createTextNode(res.getCelltype()));
            collection.setAttributeNode(cell_type);
        }

        if (res.getMag() != null) {
            Attr magnification = doc.createAttribute("MAG");
            magnification.appendChild(doc.createTextNode(res.getMag()));
            collection.setAttributeNode(magnification);
        }

        if (res.getComment() != null) {
            Attr comment = doc.createAttribute("COMMENT");
            comment.appendChild(doc.createTextNode(res.getComment()));
            collection.setAttributeNode(comment);
        }

        if (res.getYear() != -1 & res.getMonth() != -1 & res.getDay() != -1) {
            Attr date = doc.createAttribute("DATE");
            date.appendChild(doc.createTextNode(String.valueOf(res.getDay())+"/"+String.valueOf(res.getMonth())+"/"+String.valueOf(res.getYear())));
            collection.setAttributeNode(date);
        }

        if (res.getHour() != -1 & res.getMin() != -1 & res.getSec() != -1) {
            DecimalFormat time_df = new DecimalFormat("00");
            Attr time = doc.createAttribute("TIME");

            time.appendChild(doc.createTextNode(String.valueOf(time_df.format(res.getHour()))+":"+String.valueOf(time_df.format(res.getMin()))+":"+String.valueOf(time_df.format(res.getSec()))));
            collection.setAttributeNode(time);
        } else if (res.getHour() != -1 & res.getMin() != -1 & res.getSec() == -1) {
            DecimalFormat time_df = new DecimalFormat("00");
            Attr time = doc.createAttribute("TIME");

            time.appendChild(doc.createTextNode(String.valueOf(time_df.format(res.getHour()))+":"+String.valueOf(time_df.format(res.getMin()))));
            collection.setAttributeNode(time);
        }

        return collection;

    }
}
