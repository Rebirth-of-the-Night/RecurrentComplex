/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.gui;

import ivorius.ivtoolkit.tools.IvTranslations;
import ivorius.reccomplex.files.loading.ResourceDirectory;
import ivorius.reccomplex.gui.table.*;
import ivorius.reccomplex.utils.SaveDirectoryData;
import net.minecraft.util.text.TextFormatting;

import java.util.function.Supplier;

/**
 * Created by lukas on 31.08.16.
 */
public class TableElementSaveDirectory
{
    public static TableElement create(SaveDirectoryData data, Supplier<String> idSupplier, TableDelegate delegate)
    {
        final String id = idSupplier.get();

        TableCellEnum<ResourceDirectory> cellFolder = new TableCellEnum<>("activeFolder", data.getDirectory(),
                TableCellEnum.options(ResourceDirectory.values(), d -> IvTranslations.format("reccomplex.structure.savePath", String.format("%s/%s%s", TextFormatting.AQUA, d.subDirectoryName(), TextFormatting.RESET)), null));
        cellFolder.addPropertyConsumer(cell ->
        {
            data.setDirectory(cellFolder.getPropertyValue());
            delegate.reloadData(); // Delete other cell might get added
        });

        if (data.getDirectory().isActive() ? data.getFilesInInactive().contains(id) : data.getFilesInActive().contains(id))
        {
            String path = data.getDirectory().opposite().subDirectoryName();
            TableCellBoolean cellDelete = new TableCellBoolean("deleteOther", data.isDeleteOther(),
                    IvTranslations.format("reccomplex.structure.deleteOther.true", TextFormatting.RED, TextFormatting.RESET, String.format("%s/%s%s", TextFormatting.AQUA, path, TextFormatting.RESET)),
                    IvTranslations.format("reccomplex.structure.deleteOther.false", TextFormatting.YELLOW, TextFormatting.RESET, String.format("%s/%s%s", TextFormatting.AQUA, path, TextFormatting.RESET)));
            cellDelete.addPropertyConsumer(cell -> data.setDeleteOther(cellDelete.getPropertyValue()));
            cellDelete.setTooltip(IvTranslations.formatLines("reccomplex.structure.deleteOther.tooltip",
                    TextFormatting.AQUA + ResourceDirectory.INACTIVE.subDirectoryName() + TextFormatting.RESET,
                    TextFormatting.AQUA + ResourceDirectory.ACTIVE.subDirectoryName() + TextFormatting.RESET));

            return new TableElementCell(new TableCellMulti(cellFolder, cellDelete));
        }

        return new TableElementCell(new TableCellMulti(cellFolder, new TableCellButton("", "", "-", false)));
    }
}
