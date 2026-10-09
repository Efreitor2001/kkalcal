package ru.dietdiary.offline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable fun NavGlyph(index:Int) {
    val color=MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        val s=size.width/24;val stroke=Stroke(1.7f*s,cap=StrokeCap.Round)
        fun line(x1:Float,y1:Float,x2:Float,y2:Float) = drawLine(color,Offset(x1*s,y1*s),Offset(x2*s,y2*s),1.7f*s,StrokeCap.Round)
        when(index) {
            0->{drawCircle(color,8*s,Offset(12*s,12*s),style=stroke);drawCircle(color,4.5f*s,Offset(12*s,12*s),style=stroke)}
            1->{for(x in listOf(4f,14f))for(y in listOf(4f,14f)) drawRoundRect(color,Offset(x*s,y*s),Size(6*s,6*s),CornerRadius(1.5f*s),style=stroke)}
            2->{drawRoundRect(color,Offset(5*s,3*s),Size(14*s,18*s),CornerRadius(2*s),style=stroke);line(9f,8f,15f,8f);line(9f,12f,15f,12f);line(9f,16f,13f,16f)}
            3->{line(4f,20f,20f,20f);line(6f,16f,6f,11f);line(12f,16f,12f,4f);line(18f,16f,18f,8f)}
            else->{for(x in listOf(5f,12f,19f))drawCircle(color,1.7f*s,Offset(x*s,12*s))}
        }
    }
}
