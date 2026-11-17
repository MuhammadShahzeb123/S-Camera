package com.shahzeb.scamera.ui;

import android.app.FragmentManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import com.shahzeb.scamera.R;
import com.shahzeb.scamera.op.ui.OpTheme;

/** Shared presentation for the native preference screens. The preference objects, keys and
 *  widgets are retained so capability filtering, dependencies and persistence stay native.
 */
public final class SettingsPreferenceStyle {
    private SettingsPreferenceStyle() {
    }

    /** Called after each screen has finished filtering its preferences, before its list is bound. */
    public static void prepare(PreferenceGroup group) {
        if( group == null )
            return;

        for(int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if( preference instanceof PreferenceCategory ) {
                preference.setLayoutResource(R.layout.op_settings_category);
                // Categories are not selectable, but their headings should keep full contrast.
                preference.setShouldDisableView(false);
            }
            else {
                preference.setLayoutResource(R.layout.op_settings_preference);
                if( preference instanceof PreferenceScreen && preference.getWidgetLayoutResource() == 0 ) {
                    preference.setWidgetLayoutResource(R.layout.op_settings_chevron);
                }
            }
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
                preference.setSingleLineTitle(false);
                preference.setIconSpaceReserved(false);
            }
            if( preference instanceof PreferenceGroup ) {
                prepare((PreferenceGroup)preference);
            }
        }
    }

    /** Keep the framework's list and adapter, adding only a persistent page header around it. */
    public static View wrap(PreferenceFragment fragment, View preferenceContent) {
        Context context = fragment.getActivity();
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(context.getColor(R.color.op_settings_background));
        page.setClickable(true);
        page.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(OpTheme.dp(context, 76));
        header.setPadding(OpTheme.dp(context, 12), OpTheme.dp(context, 8),
                OpTheme.dp(context, 24), OpTheme.dp(context, 8));

        ImageButton back = new ImageButton(context);
        back.setImageResource(R.drawable.op_ic_arrow_back);
        back.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        back.setContentDescription(context.getString(androidx.appcompat.R.string.abc_action_bar_up_description));
        back.setPadding(OpTheme.dp(context, 12), OpTheme.dp(context, 12),
                OpTheme.dp(context, 12), OpTheme.dp(context, 12));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask));
        back.setOnClickListener(view -> {
            FragmentManager manager = fragment.getFragmentManager();
            if( fragment.isAdded() && manager != null ) {
                // Disable immediately so repeated taps cannot pop more than this page.
                view.setEnabled(false);
                manager.popBackStack();
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(OpTheme.dp(context, 48), OpTheme.dp(context, 48)));

        TextView title = new TextView(context);
        title.setText(pageTitle(fragment));
        title.setTextColor(context.getColor(R.color.op_settings_text));
        title.setTextSize(fragment.getTargetFragment() == null ? 26 : 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(OpTheme.dp(context, 12), OpTheme.dp(context, 6), 0, OpTheme.dp(context, 6));
        ViewCompat.setAccessibilityHeading(title, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        preferenceContent.setPadding(0, 0, 0, 0);
        View nativeTitle = preferenceContent.findViewById(android.R.id.title);
        if( nativeTitle != null ) {
            // PreferenceFragment.bindPreferences() otherwise displays the screen title a second time.
            nativeTitle.setVisibility(View.GONE);
            nativeTitle.setId(View.NO_ID);
        }
        page.addView(preferenceContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    /** Apply after PreferenceFragment.onViewCreated(), which may restore the theme's divider. */
    public static void styleList(View page) {
        Context context = page.getContext();
        ListView list = page.findViewById(android.R.id.list);
        if( list != null ) {
            list.setBackgroundColor(Color.TRANSPARENT);
            list.setDivider(null);
            list.setDividerHeight(0);
            list.setPadding(OpTheme.dp(context, 16), OpTheme.dp(context, 4),
                    OpTheme.dp(context, 16), OpTheme.dp(context, 24));
            list.setClipToPadding(false);
            list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        }
    }

    private static CharSequence pageTitle(PreferenceFragment fragment) {
        CharSequence title = fragment.getPreferenceScreen().getTitle();
        if( title == null || title.length() == 0 )
            return fragment.getString(R.string.action_settings);

        // Existing translated navigation labels may end in an ellipsis; a page title does not need it.
        String text = title.toString().trim();
        if( text.endsWith("\u2026") )
            return text.substring(0, text.length() - 1);
        if( text.endsWith("...") )
            return text.substring(0, text.length() - 3);
        return text;
    }
}
