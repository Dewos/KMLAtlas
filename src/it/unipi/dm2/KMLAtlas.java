package it.unipi.dm2;

import de.micromata.opengis.kml.v_2_2_0.ColorMode;
import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Folder;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.KmlFactory;
import de.micromata.opengis.kml.v_2_2_0.LineString;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import de.micromata.opengis.kml.v_2_2_0.Style;
import de.micromata.opengis.kml.v_2_2_0.TimeSpan;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.postgis.Geometry;
import org.postgis.PGgeometry;
import org.postgis.Point;

/**
 * Convertitore m-atlas > google earth (kml)
 * Scritto a casaccio in 3 (+4) ore... bbbbbut it works :)
 * 
 * @author Piro Fabio
 */
public class KMLAtlas
{
    final private static String USER = "postgres";
    final private static String PASS = "admin";
    final private static String URL = "jdbc:postgresql://localhost:5432/template_postgis_20";

    public static void main(String[] args) throws SQLException, ClassNotFoundException
    {
        KMLAtlas.convertMovingPoints("public.traj_complete", new File("paths.kml"));
    }
    
    /**
     * Export Moving Points into a new KML file
     * 
     * @param table any query with m-atlas output (tabl + stats)
     * @param output any new file
     * @throws java.sql.SQLException
     * @throws java.lang.ClassNotFoundException
     */
    public static void convertMovingPoints(String table, File output) throws SQLException, ClassNotFoundException
    {
        // Create new KML file
        final Kml kml = KmlFactory.createKml();
        final Document document = kml.createAndSetDocument();
        final Folder folder = document.createAndAddFolder().withName(table);

        // Styling
        final Style style = document.createAndAddStyle().withId("random_color");
        style.createAndSetLineStyle().withColorMode(ColorMode.RANDOM).withWidth(1.6);

        // Open Postgress Connection
        Class.forName("org.postgresql.Driver");
        Connection conn = DriverManager.getConnection(URL, USER, PASS);
        ((org.postgresql.PGConnection) conn).addDataType("geometry", "org.postgis.PGgeometry");
        
        try (Statement statement = conn.createStatement()) 
        {
            // Check table and join stats
            String query = 
                      " SELECT t.*, s.n_points, s.length, s.duration, s.time_start "
                    + " FROM " + table + " t, " + table + "_stats s "
                    + " WHERE t.id = s.id "
                    + " ORDER BY t.id";
            
            ResultSet resultSet = statement.executeQuery(query);
            Folder userFolder = null;
            
            while (resultSet.next()) 
            {  
                // Remove M-Atlas prefix for userId
                String idSplit = resultSet.getString("id").split("_")[0];
                
                if(userFolder == null || ! userFolder.getId().startsWith(idSplit))
                {
                    // A new forlder for each user
                    userFolder = folder.createAndAddFolder().withId(idSplit).withName("user: " + idSplit);
                }
                
                // Retrieve data
                int points = resultSet.getInt("n_points");
                Double length = resultSet.getDouble("length");
                Double duration = resultSet.getDouble("duration");
                Timestamp dateStart = resultSet.getTimestamp("time_start");
                Timestamp dateEnd = new Timestamp(dateStart.getTime() + duration.intValue() * 60 * 1000);
                Geometry geom = ((PGgeometry) resultSet.getObject("object")).getGeometry();

                // @see https://developers.google.com/kml/documentation/kmlreference#timestamp
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                
                // Generate span for path (start\end)
                TimeSpan beginEndTimeSpan = (new TimeSpan())
                                        .withBegin(dateFormat.format(dateStart))
                                        .withEnd(dateFormat.format(dateEnd));
                
                // Only Linestring for paths
                if (geom.getType() == Geometry.LINESTRING)
                {
                    // Add placemark to the map for the path
                    Placemark pl = userFolder.createAndAddPlacemark()
                                    .withStyleUrl("#random_color")
                                    .withTimePrimitive(beginEndTimeSpan)
                                    .withName("#" + resultSet.getRow() + " path")
                                    .withDescription(
                                            "Start: " + beginEndTimeSpan.getBegin() + "\n" +
                                            "Duration: " + duration.intValue() + " min | " +
                                            "Lenght: " + length.intValue() + " mt \n" +
                                            "End: " + beginEndTimeSpan.getEnd()+ "\n" +
                                            "Points: " + points + "\n" +
                                            "UserId: " + idSplit
                                    );

                    // Starts a new line-path
                    LineString paths = pl.createAndSetLineString();

                    // Cicles all the points of the founded path
                    for(int i = 0; i < geom.numPoints(); i++)
                    {
                        // Add the next point
                        Point point = geom.getPoint(i);
                        paths.addToCoordinates(point.getX() + ", " + point.getY() + ", " + 0 /* point.getZ() */)
                            .withExtrude(Boolean.TRUE)
                            .withTessellate(Boolean.TRUE);
                    }
                }
            }
            
            // Output the kml file and open it
            kml.marshal(output);
            
            // Stats google earth (warning: rapid develop here. Don't try this at home :)
            Desktop.getDesktop().open(output);
        }
        catch (IOException ex) 
        {
            Logger.getLogger(KMLAtlas.class.getName()).log(Level.SEVERE, null, ex);
        }            
        finally
        {
            conn.close();
        }
    }
}